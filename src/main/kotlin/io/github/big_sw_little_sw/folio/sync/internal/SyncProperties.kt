package io.github.big_sw_little_sw.folio.sync.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `folio.sync` (ADR 0032): a ConfigSet is due [interval] after its last sync; each consecutive failure doubles that
 * delay, up to [maxBackoff]. Every [pollInterval], each instance claims due ConfigSets for its free fetch slots, of
 * which it has [maxConcurrentFetches].
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
        require(maxConcurrentFetches >= 1) { "folio.sync.max-concurrent-fetches must be at least 1" }
    }

    private companion object {
        const val DEFAULT_INTERVAL_MINUTES = 1L
        const val DEFAULT_POLL_INTERVAL_SECONDS = 10L
        const val DEFAULT_MAX_BACKOFF_MINUTES = 30L
        const val DEFAULT_MAX_CONCURRENT_FETCHES = 4
    }
}
