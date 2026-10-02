package io.github.big_sw_little_sw.folio.consumption.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.consumption.TooManyFetchesException
import io.github.big_sw_little_sw.folio.source.RevisionRef
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceAccessFailedException
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.sync.SyncedRevisions
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Fetches for reads of synced revisions that this instance's cache lacks (ADR 0036), bounded so that reads, anonymous
 * ones included, cannot make the instance fetch without limit:
 * - at most `folio.consumption.max-concurrent-fetches` at once; a read beyond that fails at once, it does not queue;
 * - a read waits at most [LOCK_WAIT] for another fetch of the same ConfigSet, then fails;
 * - a commit still missing after a fetch is not fetched for again on this instance for [MISSING_RETRY];
 * - nothing is fetched while the last sync found the repository too large, since the fetch would fail the same way.
 */
@Component
class OnDemandFetches(
    private val sources: SourceAccess,
    private val revisions: SyncedRevisions,
    properties: ConsumptionProperties,
) {
    private val slots = Semaphore(properties.maxConcurrentFetches)

    /** When, by [System.nanoTime], each commit found missing may be fetched for again. */
    private val missing = ConcurrentHashMap<Pair<ConfigSetId, String>, Long>()

    /**
     * Makes sure the cache holds [commit] of [configSet], fetching if needed, and returns whether it does. Throws
     * [TooManyFetchesException] or `SourceBusyException` when it cannot fetch now, and [SourceAccessFailedException]
     * when the fetch fails.
     */
    fun fetch(
        configSet: ConfigSet,
        commit: RevisionRef.Commit,
    ): Boolean {
        val key = configSet.id to commit.id
        if (missing[key]?.let { System.nanoTime() - it < 0 } == true) return false
        if (revisions.lastErrorCode(configSet.id) == SourceFailure.REPOSITORY_TOO_LARGE.name) {
            throw SourceAccessFailedException(SourceFailure.REPOSITORY_TOO_LARGE)
        }
        if (!slots.tryAcquire()) throw TooManyFetchesException()
        val found =
            try {
                sources.fetchIfMissing(configSet.id.value, configSet.source, commit, LOCK_WAIT)
            } finally {
                slots.release()
            }
        if (found) missing.remove(key) else remember(key)
        return found
    }

    private fun remember(key: Pair<ConfigSetId, String>) {
        val now = System.nanoTime()
        missing.values.removeIf { now - it >= 0 }
        missing[key] = now + MISSING_RETRY.toNanos()
    }

    companion object {
        /** How long a read waits for another fetch of the same ConfigSet before it gives up with 503. */
        val LOCK_WAIT: Duration = Duration.ofSeconds(5)

        /** How long a commit found missing is not fetched for again: the default sync interval. */
        val MISSING_RETRY: Duration = Duration.ofMinutes(1)
    }
}
