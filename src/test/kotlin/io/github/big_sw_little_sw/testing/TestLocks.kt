package io.github.big_sw_little_sw.testing

import org.springframework.boot.test.context.TestComponent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors

/**
 * This component runs race tests whose result does not depend on timing. A holder transaction takes locks, and the
 * tasks start while the holder keeps them. The holder commits only when each task waits for a lock in PostgreSQL.
 */
@TestComponent
class TestLocks(private val transactions: TransactionTemplate, private val jdbc: JdbcClient) {
    /**
     * Runs [hold] in a transaction, then runs [tasks] in parallel while that transaction is open. Each task must wait
     * for a lock, or the method fails. Each task gets its own virtual thread. With a pool of platform threads, a task
     * could wait for a free thread and not for the lock.
     *
     * After the tasks wait, the holder runs [beforeCommit] and commits. [beforeCommit] lets the holder take a lock
     * after the tasks hold theirs, so a test can find a lock-order cycle.
     */
    fun whileHeld(hold: () -> Unit, vararg tasks: () -> Any?, beforeCommit: () -> Unit = {}): List<Result<Any?>> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val holder: (() -> Unit) -> Unit = { held ->
                transactions.executeWithoutResult {
                    hold()
                    held()
                    beforeCommit()
                }
            }
            val running =
                whileHolding(holder) {
                    val started = tasks.map { task -> executor.submit<Result<Any?>> { runCatching(task) } }
                    awaitUntil("The tasks wait for a lock") { lockWaiters() >= tasks.size }
                    started
                }
            running.map { it.await() }
        }

    /**
     * A task that waits for a lock has exactly one request that is not granted: an advisory lock, a transaction ID or a
     * row. Test classes run one at a time, so no other test adds requests.
     */
    private fun lockWaiters(): Int =
        jdbc.sql("select count(*) from pg_locks where not granted").query(Int::class.java).single()
}
