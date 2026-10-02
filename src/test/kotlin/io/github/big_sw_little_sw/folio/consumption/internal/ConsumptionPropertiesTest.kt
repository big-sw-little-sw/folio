package io.github.big_sw_little_sw.folio.consumption.internal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConsumptionPropertiesTest {
    @Test
    fun `latest is cached for 30 seconds by default`() {
        assertEquals(Duration.ofSeconds(30), ConsumptionProperties().latestMaxAge)
    }

    @Test
    fun `the latest max age may be zero but not negative`() {
        assertEquals(Duration.ZERO, ConsumptionProperties(Duration.ZERO).latestMaxAge)
        assertFailsWith<IllegalArgumentException> { ConsumptionProperties(Duration.ofSeconds(-1)) }
    }
}
