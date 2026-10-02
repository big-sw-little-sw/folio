package io.github.big_sw_little_sw.folio.source.internal

import java.time.Duration

/**
 * The wall-clock deadline of one fetch, from before its ls-remote to the end of the transfer (ADR 0033). [SshConnections]
 * enforces it: connects get no more than the time left, and at the deadline it cuts every command it started.
 */
class FetchDeadline(
    duration: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val end = nanoTime() + duration.toNanos()

    /** Whether the deadline was enforced; a failure after that is reported as the deadline, not as what JGit saw. */
    @Volatile
    var exceeded = false
        private set

    /** Time left, zero once the deadline has passed. Subtraction, not comparison, survives a nanoTime wrap-around. */
    fun remaining(): Duration = Duration.ofNanos(maxOf(0L, end - nanoTime()))

    fun expire() {
        exceeded = true
    }
}
