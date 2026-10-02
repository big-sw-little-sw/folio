package io.github.big_sw_little_sw.folio.source.internal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchDeadlineTest {
    private var now = 0L

    @Test
    fun `the time left runs down to zero and stays there`() {
        val deadline = FetchDeadline(Duration.ofSeconds(10)) { now }

        now = Duration.ofSeconds(4).toNanos()
        assertEquals(Duration.ofSeconds(6), deadline.remaining())
        now = Duration.ofSeconds(11).toNanos()
        assertEquals(Duration.ZERO, deadline.remaining())
    }

    @Test
    fun `the deadline counts as exceeded only once it has been enforced`() {
        val deadline = FetchDeadline(Duration.ofSeconds(10)) { now }

        assertFalse(deadline.exceeded)
        deadline.expire()
        assertTrue(deadline.exceeded)
    }

    @Test
    fun `as a progress monitor it cancels JGit's work from the deadline on`() {
        val deadline = FetchDeadline(Duration.ofSeconds(10)) { now }

        now = Duration.ofSeconds(10).toNanos() - 1
        assertFalse(deadline.isCancelled())
        now = Duration.ofSeconds(10).toNanos()
        assertTrue(deadline.isCancelled())
        assertTrue(deadline.exceeded)
    }

    @Test
    fun `the time left survives a wrap-around of the nanosecond clock`() {
        now = Long.MAX_VALUE - 5
        val deadline = FetchDeadline(Duration.ofNanos(10)) { now }

        now += 9
        assertEquals(Duration.ofNanos(1), deadline.remaining())
        now += 1
        assertEquals(Duration.ZERO, deadline.remaining())
    }
}
