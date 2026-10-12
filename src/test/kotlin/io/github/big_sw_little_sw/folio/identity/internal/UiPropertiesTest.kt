package io.github.big_sw_little_sw.folio.identity.internal

import java.time.Duration
import kotlin.test.*

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
