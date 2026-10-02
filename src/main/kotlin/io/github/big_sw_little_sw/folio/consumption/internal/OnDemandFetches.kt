package io.github.big_sw_little_sw.folio.consumption.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.consumption.TooManyFetchesException
import io.github.big_sw_little_sw.folio.source.RevisionRef
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceAccessFailedException
import io.github.big_sw_little_sw.folio.source.SourceBusyException
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.sync.SyncedRevisions
import io.micrometer.core.instrument.MeterRegistry
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
 *
 * Each call is counted by its result (ADR 0039).
 */
@Component
class OnDemandFetches(
    private val sources: SourceAccess,
    private val revisions: SyncedRevisions,
    properties: ConsumptionProperties,
    private val meters: MeterRegistry,
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
        if (missing[key]?.let { System.nanoTime() - it < 0 } == true) {
            count(FetchResult.MISSING)
            return false
        }
        if (revisions.lastErrorCode(configSet.id) == SourceFailure.REPOSITORY_TOO_LARGE.name) {
            count(FetchResult.TOO_LARGE)
            throw SourceAccessFailedException(SourceFailure.REPOSITORY_TOO_LARGE)
        }
        val found = fetchInSlot(configSet, commit)
        if (found) missing.remove(key) else remember(key)
        return found
    }

    private fun fetchInSlot(
        configSet: ConfigSet,
        commit: RevisionRef.Commit,
    ): Boolean {
        if (!slots.tryAcquire()) {
            count(FetchResult.BUSY)
            throw TooManyFetchesException()
        }
        // Anything else that escapes, such as SourceAccessFailedException, is a failed fetch.
        var result = FetchResult.FAILED
        try {
            val found = sources.fetchIfMissing(configSet.id.value, configSet.source, commit, LOCK_WAIT)
            result = if (found) FetchResult.FETCHED else FetchResult.MISSING
            return found
        } catch (e: SourceBusyException) {
            result = FetchResult.BUSY
            throw e
        } finally {
            slots.release()
            count(result)
        }
    }

    private fun count(result: FetchResult) {
        meters.counter("folio.consumption.fetches", "result", result.tag).increment()
    }

    private fun remember(key: Pair<ConfigSetId, String>) {
        val now = System.nanoTime()
        missing.values.removeIf { now - it >= 0 }
        missing[key] = now + MISSING_RETRY.toNanos()
    }

    /** The `result` tag: whether the cache now holds the commit, or why not. */
    private enum class FetchResult(
        val tag: String,
    ) {
        FETCHED("fetched"),

        /** Still missing after a fetch, or found missing by one within [MISSING_RETRY]. */
        MISSING("missing"),

        /** Too many on-demand fetches, or another fetch of the ConfigSet held the lock too long. */
        BUSY("busy"),

        /** Refused without fetching: the last sync found the repository too large. */
        TOO_LARGE("too_large"),
        FAILED("failed"),
    }

    companion object {
        /** How long a read waits for another fetch of the same ConfigSet before it gives up with 503. */
        val LOCK_WAIT: Duration = Duration.ofSeconds(5)

        /** How long a commit found missing is not fetched for again: the default sync interval. */
        val MISSING_RETRY: Duration = Duration.ofMinutes(1)
    }
}
