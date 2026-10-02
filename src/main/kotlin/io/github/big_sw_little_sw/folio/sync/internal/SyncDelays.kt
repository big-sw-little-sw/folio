package io.github.big_sw_little_sw.folio.sync.internal

import java.time.Duration

/**
 * Covers what the fetch deadline does not: connecting, `ls-remote` stalls up to JGit's idle timeout, and recording
 * the result (ADR 0032).
 */
private val LEASE_MARGIN: Duration = Duration.ofMinutes(2)

/**
 * How long after an attempt the ConfigSet is due again: [interval] after a success, doubled for each of the
 * [consecutiveFailures] that the attempt leaves, but never more than [maxBackoff].
 */
fun nextSyncDelay(
    interval: Duration,
    maxBackoff: Duration,
    consecutiveFailures: Int,
): Duration {
    var delay = interval
    // Stops doubling at the cap, so that a long run of failures cannot overflow.
    repeat(consecutiveFailures) {
        if (delay >= maxBackoff) return maxBackoff
        delay = delay.multipliedBy(2)
    }
    return minOf(delay, maxBackoff)
}

/**
 * How long a claim holds a ConfigSet: long enough for a fetch that runs to its deadline. Should a sync outlast it,
 * another instance may fetch into its own cache at the same time, and only the newer lease records its result.
 */
fun leaseDuration(fetchDeadline: Duration): Duration = fetchDeadline + LEASE_MARGIN
