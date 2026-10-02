package io.github.big_sw_little_sw.folio.sync.internal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncDelaysTest {
    private val interval = Duration.ofMinutes(1)
    private val maxBackoff = Duration.ofMinutes(30)

    @Test
    fun `after a success the ConfigSet is due one interval later`() {
        assertEquals(interval, nextSyncDelay(interval, maxBackoff, 0))
    }

    @Test
    fun `each consecutive failure doubles the delay`() {
        val delays = (1..4).map { nextSyncDelay(interval, maxBackoff, it).toMinutes() }

        assertEquals(listOf(2L, 4L, 8L, 16L), delays)
    }

    @Test
    fun `the delay never exceeds the maximum backoff, however many failures there were`() {
        assertEquals(maxBackoff, nextSyncDelay(interval, maxBackoff, 5))
        assertEquals(maxBackoff, nextSyncDelay(interval, maxBackoff, Int.MAX_VALUE))
        assertEquals(interval, nextSyncDelay(interval, interval, 3))
    }

    @Test
    fun `a lease outlasts the fetch deadline`() {
        val deadline = Duration.ofMinutes(5)

        assertTrue(leaseDuration(deadline) > deadline)
    }
}
