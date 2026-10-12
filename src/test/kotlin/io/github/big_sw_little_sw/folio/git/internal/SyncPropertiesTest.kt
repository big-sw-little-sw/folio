package io.github.big_sw_little_sw.folio.git.internal

import java.time.Duration
import kotlin.test.*

/**
 * These tests check the settings under `folio.sync`, the limit of the fetch on demand and the backoff of sync. A value
 * that is not valid stops the start of Folio, and the message names the property.
 */
class SyncPropertiesTest {
    @Test
    fun `the defaults are 1 minute, 10 seconds, 30 minutes, 4 fetches and 2 fetches on demand`() {
        val properties = SyncProperties()

        assertEquals(Duration.ofMinutes(1), properties.interval)
        assertEquals(Duration.ofSeconds(10), properties.pollInterval)
        assertEquals(Duration.ofMinutes(30), properties.maxBackoff)
        assertEquals(4, properties.maxConcurrentFetches)
        assertEquals(2, OnDemandProperties().maxConcurrentFetches)
    }

    @Test
    fun `each consecutive failure doubles the delay, to a maximum of the maximum backoff`() {
        val properties = SyncProperties()

        val delays = (0..6).map { properties.delayAfter(it).toMinutes() }

        assertEquals(listOf(1L, 2, 4, 8, 16, 30, 30), delays)
        assertEquals(Duration.ofMinutes(30), properties.delayAfter(Int.MAX_VALUE))
    }

    @Test
    fun `values that are not valid stop the start`() {
        val messages =
            listOf(
                { SyncProperties(interval = Duration.ZERO) },
                { SyncProperties(pollInterval = Duration.ZERO) },
                { SyncProperties(maxBackoff = Duration.ofSeconds(1)) },
                { SyncProperties(maxConcurrentFetches = 0) },
                { OnDemandProperties(maxConcurrentFetches = 0) },
            ).map { assertFailsWith<IllegalArgumentException> { it() }.message }

        assertEquals(
            listOf(
                "folio.sync.interval must be positive",
                "folio.sync.poll-interval must be positive",
                "folio.sync.max-backoff must not be shorter than folio.sync.interval",
                "folio.sync.max-concurrent-fetches must be 1 or more",
                "folio.content.max-concurrent-fetches must be 1 or more",
            ),
            messages,
        )
    }
}
