package io.github.big_sw_little_sw.folio.consumption.internal

import org.springframework.util.unit.DataSize
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConsumptionPropertiesTest {
    @Test
    fun `defaults are valid`() {
        val properties = ConsumptionProperties()

        assertEquals(Duration.ofSeconds(30), properties.latestMaxAge)
        assertEquals(2, properties.maxConcurrentFetches)
        assertEquals(10 * 1024 * 1024, properties.maxFileBytes)
    }

    @Test
    fun `the latest max age may be zero but not negative`() {
        assertEquals(Duration.ZERO, ConsumptionProperties(Duration.ZERO).latestMaxAge)
        assertFailsWith<IllegalArgumentException> { ConsumptionProperties(Duration.ofSeconds(-1)) }
    }

    @Test
    fun `there must be a fetch slot and a file size that fits in memory`() {
        assertFailsWith<IllegalArgumentException> { ConsumptionProperties(maxConcurrentFetches = 0) }
        assertFailsWith<IllegalArgumentException> { ConsumptionProperties(maxFileSize = DataSize.ofBytes(0)) }
        assertFailsWith<IllegalArgumentException> { ConsumptionProperties(maxFileSize = DataSize.ofGigabytes(4)) }
    }
}
