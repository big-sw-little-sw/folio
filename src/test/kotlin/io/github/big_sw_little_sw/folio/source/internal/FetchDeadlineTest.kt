package io.github.big_sw_little_sw.folio.source.internal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchDeadlineTest {
    private var now = 0L

    @Test
    fun `a fetch is not cancelled before the deadline`() {
        val deadline = FetchDeadline(Duration.ofSeconds(10)) { now }
        now = Duration.ofSeconds(10).toNanos() - 1

        assertFalse(deadline.isCancelled())
        assertFalse(deadline.exceeded)
    }

    @Test
    fun `a fetch is cancelled from the deadline on, and the deadline counts as exceeded once JGit has been told`() {
        val deadline = FetchDeadline(Duration.ofSeconds(10)) { now }
        now = Duration.ofSeconds(10).toNanos()

        assertFalse(deadline.exceeded)
        assertTrue(deadline.isCancelled())
        assertTrue(deadline.exceeded)
    }

    @Test
    fun `the deadline holds across a wrap-around of the nanosecond clock`() {
        now = Long.MAX_VALUE - 5
        val deadline = FetchDeadline(Duration.ofNanos(10)) { now }

        now += 9
        assertFalse(deadline.isCancelled())
        now += 1
        assertTrue(deadline.isCancelled())
    }
}
