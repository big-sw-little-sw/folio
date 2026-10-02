package io.github.big_sw_little_sw.folio.source.internal

import org.eclipse.jgit.lib.ProgressMonitor
import java.time.Duration

/**
 * The wall-clock deadline of one fetch, from before its ls-remote to the end of the transfer (ADR 0033). [SshConnections]
 * enforces it on the connection: connects get no more than the time left, and at the deadline it cuts every command it
 * started. As the fetch's progress monitor it also stops the work JGit does without reading from the connection, such
 * as resolving deltas and checking connectivity, which JGit asks [isCancelled] about as it goes.
 */
class FetchDeadline(
    duration: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) : ProgressMonitor {
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

    override fun isCancelled(): Boolean {
        if (remaining().isZero) expire()
        return exceeded
    }

    override fun start(totalTasks: Int) {
        // Progress is not reported.
    }

    override fun beginTask(
        title: String?,
        totalWork: Int,
    ) {
        // Progress is not reported.
    }

    override fun update(completed: Int) {
        // Progress is not reported.
    }

    override fun endTask() {
        // Progress is not reported.
    }

    override fun showDuration(enabled: Boolean) {
        // Progress is not reported.
    }
}
