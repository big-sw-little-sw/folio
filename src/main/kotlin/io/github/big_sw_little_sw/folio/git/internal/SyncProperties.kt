package io.github.big_sw_little_sw.folio.git.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The settings under `folio.sync` (design 10.6):
 *
 * - [interval] is the time from an attempt to the next due time. Each consecutive failure doubles it, to a maximum of
 *   [maxBackoff].
 * - Every [pollInterval], each instance claims due ConfigSets for the free permits of its fetch semaphore. The
 *   semaphore has [maxConcurrentFetches] permits.
 */
@ConfigurationProperties("folio.sync")
data class SyncProperties(
    val interval: Duration = Duration.ofMinutes(DEFAULT_INTERVAL_MINUTES),
    val pollInterval: Duration = Duration.ofSeconds(DEFAULT_POLL_INTERVAL_SECONDS),
    val maxBackoff: Duration = Duration.ofMinutes(DEFAULT_MAX_BACKOFF_MINUTES),
    val maxConcurrentFetches: Int = DEFAULT_MAX_CONCURRENT_FETCHES,
) {
    init {
        require(interval.isPositive) { "folio.sync.interval must be positive" }
        require(pollInterval.isPositive) { "folio.sync.poll-interval must be positive" }
        require(maxBackoff >= interval) { "folio.sync.max-backoff must not be shorter than folio.sync.interval" }
        require(maxConcurrentFetches >= 1) { "folio.sync.max-concurrent-fetches must be 1 or more" }
    }

    /**
     * Returns the time from an attempt to the next due time: [interval], doubled for each of the [consecutiveFailures]
     * that the attempt leaves, to a maximum of [maxBackoff].
     */
    fun delayAfter(consecutiveFailures: Int): Duration {
        var delay = interval
        // The loop stops at the maximum, so a long series of failures cannot overflow.
        repeat(consecutiveFailures) {
            if (delay >= maxBackoff) return maxBackoff
            delay = delay.multipliedBy(2)
        }
        return minOf(delay, maxBackoff)
    }

    private companion object {
        const val DEFAULT_INTERVAL_MINUTES = 1L
        const val DEFAULT_POLL_INTERVAL_SECONDS = 10L
        const val DEFAULT_MAX_BACKOFF_MINUTES = 30L
        const val DEFAULT_MAX_CONCURRENT_FETCHES = 4
    }
}
