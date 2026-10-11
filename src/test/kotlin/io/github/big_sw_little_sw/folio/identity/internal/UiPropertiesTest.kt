package io.github.big_sw_little_sw.folio.identity.internal

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UiPropertiesTest {
    @Test
    fun `a session ends after one hour without use by default`() {
        assertEquals(Duration.ofHours(1), UiProperties().sessionTimeout)
    }

    @Test
    fun `the session timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> { UiProperties(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { UiProperties(Duration.ofMinutes(-1)) }
    }
}
