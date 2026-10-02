package io.github.big_sw_little_sw.folio.source.internal

import org.eclipse.jgit.lib.ProgressMonitor
import java.time.Duration

/**
 * Cancels a JGit fetch once [deadline] has passed since construction (ADR 0033). JGit asks [isCancelled] while it
 * negotiates and after each object it receives, so a fetch that keeps receiving stops soon after the deadline. A
 * connection that sends nothing is bounded by JGit's idle timeout instead, and `ls-remote` takes no monitor.
 */
class FetchDeadline(
    deadline: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) : ProgressMonitor {
    private val end = nanoTime() + deadline.toNanos()

    /** Whether this monitor has told JGit to cancel; JGit then fails the fetch as a transport error. */
    @Volatile
    var exceeded = false
        private set

    override fun isCancelled(): Boolean {
        // Subtraction, not comparison, so that a wrap-around of nanoTime cannot end the deadline early.
        if (nanoTime() - end >= 0) exceeded = true
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
