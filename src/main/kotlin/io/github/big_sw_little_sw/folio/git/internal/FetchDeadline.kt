package io.github.big_sw_little_sw.folio.git.internal

import org.eclipse.jgit.lib.ProgressMonitor
import java.time.Duration

/**
 * The wall-clock deadline of one fetch (design 10.7). The deadline starts before the wait for the fetch lock and the
 * `ls-remote`, and it ends after delta resolution. [SshConnections] enforces the deadline on the connection. A connect
 * gets no more than the remaining time, and at the deadline a watchdog cuts each command ([DeadlineSessions]). As the
 * progress monitor of the fetch, the deadline also cancels local work that reads nothing from the connection, for
 * example delta resolution. JGit calls [isCancelled] during that work.
 *
 * The JGit timeout applies only to idle reads. Thus a slow server that sends data continuously never triggers it, and
 * the deadline is necessary.
 */
class FetchDeadline(duration: Duration, private val nanoTime: () -> Long = System::nanoTime) : ProgressMonitor {
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

    override fun start(totalTasks: Int) {
        // Folio does not report progress.
    }

    override fun beginTask(title: String?, totalWork: Int) {
        // Folio does not report progress.
    }

    override fun update(completed: Int) {
        // Folio does not report progress.
    }

    override fun endTask() {
        // Folio does not report progress.
    }

    override fun showDuration(enabled: Boolean) {
        // Folio does not report progress.
    }
}
