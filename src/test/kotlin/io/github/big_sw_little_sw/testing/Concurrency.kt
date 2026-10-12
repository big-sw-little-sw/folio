package io.github.big_sw_little_sw.testing

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** A test waits at most this long for another thread or for a condition. A longer wait means that the test failed. */
val TIMEOUT: Duration = Duration.ofSeconds(20)

private val POLL_INTERVAL = Duration.ofMillis(20)

/**
 * Runs [test] while another virtual thread holds a resource, for example a lock. [hold] takes the resource and then
 * calls `held`, which returns after [test] ends. Without the wait for `held`, [test] could run before [hold] takes the
 * resource. If [test] takes longer than [TIMEOUT], [hold] fails, so a test that waits for the resource cannot pass.
 */
fun <T> whileHolding(hold: (held: () -> Unit) -> Unit, test: () -> T): T {
    val holding = CountDownLatch(1)
    val release = CountDownLatch(1)
    return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        val holder =
            executor.submit {
                hold {
                    holding.countDown()
                    release.awaitOrFail("The test did not end while the holder held the resource")
                }
            }
        holding.awaitOrFail("The holder did not take the resource")
        try {
            test()
        } finally {
            release.countDown()
            holder.await()
        }
    }
}

/** Waits until [condition] is true. [what] describes the condition for the failure message. */
fun awaitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TIMEOUT.toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timeout: $what" }
        Thread.sleep(POLL_INTERVAL)
    }
}

/** Returns the result of the task, or fails after [TIMEOUT]. */
fun <T> Future<T>.await(): T = get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)

private fun CountDownLatch.awaitOrFail(message: String) = check(await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
    message
}
