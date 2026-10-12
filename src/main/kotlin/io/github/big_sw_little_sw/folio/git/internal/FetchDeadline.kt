package io.github.big_sw_little_sw.folio.git.internal

import org.eclipse.jgit.lib.EmptyProgressMonitor
import java.time.Duration

/**
 * The wall-clock deadline of one fetch. The JGit timeout applies only to idle reads. Without the deadline, a slow
 * server that sends data continuously can hold a fetch for an unlimited time.
 *
 * [IsolatedSsh] enforces the deadline on the connection. As the progress monitor of the fetch, the deadline also
 * cancels local work that reads nothing from the connection, for example delta resolution.
 */
class FetchDeadline(duration: Duration, private val nanoTime: () -> Long = System::nanoTime) : EmptyProgressMonitor() {
    private val end = nanoTime() + duration.toNanos()

    /** True after Folio enforced the deadline. Then a failure is a deadline failure, whatever exception JGit gives. */
    @Volatile
    var exceeded = false
        private set

    /** Returns the remaining time, or zero after the deadline. The subtraction stays correct if nanoTime overflows. */
    fun remaining(): Duration = Duration.ofNanos(maxOf(0L, end - nanoTime()))

    fun expire() {
        exceeded = true
    }

    override fun isCancelled(): Boolean {
        if (remaining().isZero) expire()
        return exceeded
    }
}
