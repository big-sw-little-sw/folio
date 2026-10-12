package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.catalog.SourceType
import io.github.big_sw_little_sw.testing.*
import kotlin.test.*

/** Each Source type needs exactly one adapter, or Folio does not start. */
class SourcesByTypeTest {
    @Test
    fun `each Source type gets its adapter`() {
        val adapter = InMemorySource()

        assertEquals(mapOf(SourceType.GIT to adapter), sourcesByType(listOf(adapter)))
    }

    @Test
    fun `a Source type without an adapter stops the start`() {
        val failure = assertFailsWith<IllegalStateException> { sourcesByType(emptyList()) }

        assertEquals("No ConfigSource adapter for [GIT]", failure.message)
    }

    @Test
    fun `two adapters for one Source type stop the start`() {
        assertFailsWith<IllegalStateException> { sourcesByType(listOf(InMemorySource(), InMemorySource())) }
    }
}
