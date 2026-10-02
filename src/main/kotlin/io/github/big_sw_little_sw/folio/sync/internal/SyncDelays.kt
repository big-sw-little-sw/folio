package io.github.big_sw_little_sw.folio.sync.internal

import java.time.Duration

/**
 * Covers what the fetch deadline does not cut (ADR 0032, ADR 0033): an SSH session that reaches key exchange and
 * authentication just before the deadline may take sshd's 2-minute authentication timeout, then up to 30 seconds to
 * open its command channel, before the command is cut as it starts. That leaves 30 seconds for loading the ConfigSet,
 * the size check and recording.
 */
private val LEASE_MARGIN: Duration = Duration.ofMinutes(3)

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
