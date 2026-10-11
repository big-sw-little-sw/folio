package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.RevisionId
import org.eclipse.jgit.lib.ObjectId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * The settings of the fetch on demand (design 10.8). [maxConcurrentFetches] is `folio.content.max-concurrent-fetches`:
 * the maximum number of fetches on demand that run at the same time on this instance. The name is in `folio.content`,
 * because the limit protects reads. The Git adapter enforces it, so the `git` module binds it.
 */
@ConfigurationProperties("folio.content")
data class OnDemandProperties(val maxConcurrentFetches: Int = DEFAULT_MAX_CONCURRENT_FETCHES) {
    init {
        require(maxConcurrentFetches >= 1) { "folio.content.max-concurrent-fetches must be 1 or more" }
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_FETCHES = 2
    }
}

/**
 * Fetches a published commit that the cache of this instance does not have (design 10.8). Reads can come from
 * anonymous callers, so these limits stop reads from causing too many fetches:
 *
 * - A maximum of `folio.content.max-concurrent-fetches` fetches on demand run at the same time. Above the limit, a
 *   read fails immediately with `SourceBusy`. It does not wait in a queue. Only a fetch that runs holds a permit. A
 *   read that waits for another fetch of the same ConfigSet holds none.
 * - A read waits a maximum of 5 seconds for another fetch of the same ConfigSet, then fails with `SourceBusy`.
 *   [Fetcher.fetchMissing] owns that wait.
 * - A commit that is still missing after a fetch, for example after a force push, stays missing for one sync interval.
 *   During that time, reads of the commit fail with `RevisionUnavailable` and do not fetch.
 * - If the last sync failed with `REPOSITORY_TOO_LARGE`, a read that needs a fetch fails immediately with that code,
 *   because the fetch would fail in the same way.
 */
@Component
class OnDemandFetches(
    private val fetcher: Fetcher,
    private val syncs: SyncRepository,
    properties: OnDemandProperties,
    syncProperties: SyncProperties,
) {
    private val permits = Semaphore(properties.maxConcurrentFetches)
    private val memory = syncProperties.interval

    /** For each missing commit, the time (by [System.nanoTime]) until which reads do not fetch it again. */
    private val missing = ConcurrentHashMap<MissingCommit, Long>()

    /**
     * Makes sure that the cache has the published [revision] of [configSet]. Throws `RevisionUnavailable` if the
     * commit is missing, `SourceBusy` if the fetch cannot run now, and `SourceFailed` or `SourceTimedOut` with the
     * failure code of a failed fetch.
     */
    fun fetch(configSet: ConfigSetId, source: GitSourceDefinition, revision: RevisionId) {
        val key = MissingCommit(configSet, revision)
        requireFetchable(key)
        when (fetchMissing(configSet, source, revision)) {
            Fetcher.OnDemand.FOUND -> {
                missing.remove(key)
            }

            Fetcher.OnDemand.MISSING -> {
                remember(key)
                throw ContentException.RevisionUnavailable(revision)
            }

            Fetcher.OnDemand.BUSY -> {
                throw ContentException.SourceBusy(RETRY_AFTER)
            }
        }
    }

    /** Refuses a fetch that would fail again: a commit that is still missing, or a repository that is too large. */
    private fun requireFetchable(key: MissingCommit) {
        val missingUntil = missing[key]
        if (missingUntil != null && System.nanoTime() - missingUntil < 0) {
            throw ContentException.RevisionUnavailable(key.revision)
        }
        val tooLarge = FailureCode.REPOSITORY_TOO_LARGE
        if (syncs.failure(key.configSet) == tooLarge) throw tooLarge.toContentException()
    }

    private fun fetchMissing(
        configSet: ConfigSetId,
        source: GitSourceDefinition,
        revision: RevisionId,
    ): Fetcher.OnDemand = try {
        // A published revision is always a commit ID, so fromString does not fail.
        fetcher.fetchMissing(configSet, source, ObjectId.fromString(revision.value), ::withPermit)
    } catch (failure: GitFailure) {
        throw failure.toContentException()
    }

    /** Runs [fetch] with a permit. Without a free permit, it returns false at once and does not wait in a queue. */
    private fun withPermit(fetch: () -> Unit): Boolean {
        if (!permits.tryAcquire()) return false
        try {
            fetch()
        } finally {
            permits.release()
        }
        return true
    }

    /** Remembers a missing commit. The map keeps only entries whose time has not ended, so it stays small. */
    private fun remember(key: MissingCommit) {
        val now = System.nanoTime()
        missing.values.removeIf { now - it >= 0 }
        missing[key] = now + memory.toNanos()
    }

    private data class MissingCommit(val configSet: ConfigSetId, val revision: RevisionId)

    private companion object {
        /** The `Retry-After` time of `SourceBusy`. A fetch usually ends in seconds. */
        val RETRY_AFTER: Duration = Duration.ofSeconds(5)
    }
}
