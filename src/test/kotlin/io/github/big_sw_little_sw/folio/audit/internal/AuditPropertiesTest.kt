package io.github.big_sw_little_sw.folio.audit.internal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditPropertiesTest {
    @Test
    fun `records are kept for 90 days by default`() {
        assertEquals(Duration.ofDays(90), AuditProperties().retention)
    }

    @Test
    fun `the retention must be positive`() {
        assertFailsWith<IllegalArgumentException> { AuditProperties(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { AuditProperties(Duration.ofDays(-1)) }
    }
}
