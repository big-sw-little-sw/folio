package io.github.big_sw_little_sw.folio.sync.internal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncPropertiesTest {
    @Test
    fun `defaults are valid`() {
        val properties = SyncProperties()

        assertEquals(Duration.ofMinutes(1), properties.interval)
        assertEquals(4, properties.maxConcurrentFetches)
    }

    @Test
    fun `durations must be positive, the backoff at least the interval and there must be a fetch slot`() {
        assertFailsWith<IllegalArgumentException> { SyncProperties(interval = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { SyncProperties(pollInterval = Duration.ofSeconds(-1)) }
        assertFailsWith<IllegalArgumentException> {
            SyncProperties(interval = Duration.ofMinutes(10), maxBackoff = Duration.ofMinutes(5))
        }
        assertFailsWith<IllegalArgumentException> { SyncProperties(maxConcurrentFetches = 0) }
    }
}
