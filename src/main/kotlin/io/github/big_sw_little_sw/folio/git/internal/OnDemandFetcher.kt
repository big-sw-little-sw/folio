package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.RevisionId
import io.micrometer.core.instrument.MeterRegistry
import org.eclipse.jgit.lib.ObjectId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * The setting `folio.content.max-concurrent-fetches` of [OnDemandFetcher]. The name is in `folio.content`, because the
 * limit protects reads. The Git adapter enforces the limit, so the `git` module binds it.
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
 * Fetches a published commit that the cache of this instance does not have. Reads can come from anonymous callers, so
 * these limits stop reads from causing too many fetches:
 *
 * - A maximum of `folio.content.max-concurrent-fetches` fetches on demand run at the same time. Above the limit, a
 *   read fails immediately with `SourceBusy`. It does not wait in a queue.
 * - A read waits a maximum of 5 seconds for another fetch of the same ConfigSet, then fails with `SourceBusy`.
 * - A commit that is still missing after a fetch, for example after a force push, stays missing for one sync interval.
 *   During that time, reads of the commit fail with `RevisionUnavailable` and do not fetch.
 * - If the last sync failed with `REPOSITORY_TOO_LARGE`, a read that needs a fetch fails immediately with that code,
 *   because the fetch would fail in the same way.
 *
 * The meter `folio.content.fetches` counts each read that needs a fetch, by its result. A read that waited for another
 * fetch counts as `fetched` if that fetch got the commit, and as `busy` if the wait was too long.
 */
@Component
class OnDemandFetcher(
    private val fetcher: Fetcher,
    private val cache: RepositoryCache,
    private val syncRepository: SyncRepository,
    private val gitProperties: GitProperties,
    properties: OnDemandProperties,
    syncProperties: SyncProperties,
    private val meters: MeterRegistry,
) {
    private val permits = Semaphore(properties.maxConcurrentFetches)
    private val memory = syncProperties.interval

    /** For each missing commit, the time (by [System.nanoTime]) until which reads do not fetch it again. */
    private val missing = ConcurrentHashMap<MissingCommit, Long>()

    /**
     * Makes sure that the cache has [commit], the commit of the published [revision] of [configSet]. Throws
     * `RevisionUnavailable` if the commit is missing, `SourceBusy` if the fetch cannot run now, and `SourceFailed` or
     * `SourceTimedOut` with the failure code of a failed fetch.
     */
    fun fetch(configSet: ConfigSetId, source: GitSourceDefinition, revision: RevisionId, commit: ObjectId) {
        val key = MissingCommit(configSet, revision)
        requireFetchable(key)
        // As in [Fetcher.fetch], the deadline starts before the lock wait. Thus a fetch on demand never ends later than
        // a sync fetch that starts at the same time.
        val deadline = FetchDeadline(gitProperties.fetchDeadline)
        val found =
            cache.withFetchLock(configSet, minOf(LOCK_WAIT, deadline.remaining())) {
                fetchLocked(configSet, source, commit, deadline)
            } ?: throw busy()
        if (!found) {
            count(MISSING)
            remember(key)
            throw ContentException.RevisionUnavailable(revision)
        }
        count(FETCHED)
        missing.remove(key)
    }

    /**
     * Runs with the fetch lock and returns true if the cache has [commit] at the end. A read that waited for the lock
     * usually finds [commit] at the first look, so it does not fetch. Only a fetch that runs holds a permit, and only
     * the fetch opens the key. Thus a read that finds the commit, or that gets no permit, never decrypts a key.
     */
    private fun fetchLocked(
        configSet: ConfigSetId,
        source: GitSourceDefinition,
        commit: ObjectId,
        deadline: FetchDeadline,
    ): Boolean {
        if (hasCommit(configSet, commit)) return true
        if (!permits.tryAcquire()) throw busy()
        try {
            fetcher.fetch(configSet, source, deadline = deadline)
        } catch (failure: GitFailure) {
            count(if (failure.code == FailureCode.REPOSITORY_TOO_LARGE) TOO_LARGE else FAILED)
            throw failure.toContentException()
        } finally {
            permits.release()
        }
        return hasCommit(configSet, commit)
    }

    private fun hasCommit(configSet: ConfigSetId, commit: ObjectId): Boolean =
        cache.reading(configSet, { false }) { commitOrNull(it, commit) != null }

    /** Refuses a fetch that would fail again: a commit that is still missing, or a repository that is too large. */
    private fun requireFetchable(key: MissingCommit) {
        val missingUntil = missing[key]
        if (missingUntil != null && System.nanoTime() - missingUntil < 0) {
            count(MISSING)
            throw ContentException.RevisionUnavailable(key.revision)
        }
        val tooLarge = FailureCode.REPOSITORY_TOO_LARGE
        if (syncRepository.state(key.configSet)?.failure == tooLarge) {
            count(TOO_LARGE)
            throw tooLarge.toContentException()
        }
    }

    /** Remembers a missing commit. The map keeps only entries whose time has not ended, so it stays small. */
    private fun remember(key: MissingCommit) {
        val now = System.nanoTime()
        missing.values.removeIf { now - it >= 0 }
        missing[key] = now + memory.toNanos()
    }

    private fun busy(): ContentException {
        count(BUSY)
        return ContentException.SourceBusy(RETRY_AFTER)
    }

    private fun count(result: String) {
        meters.counter(FETCHES, "result", result).increment()
    }

    private data class MissingCommit(val configSet: ConfigSetId, val revision: RevisionId)

    private companion object {
        /** How long a read waits for another fetch of the same ConfigSet. */
        val LOCK_WAIT: Duration = Duration.ofSeconds(5)

        /** The `Retry-After` time of `SourceBusy`. A fetch usually ends in seconds. */
        val RETRY_AFTER: Duration = Duration.ofSeconds(5)

        const val FETCHES = "folio.content.fetches"
        const val FETCHED = "fetched"
        const val MISSING = "missing"
        const val BUSY = "busy"
        const val TOO_LARGE = "too_large"
        const val FAILED = "failed"
    }
}
