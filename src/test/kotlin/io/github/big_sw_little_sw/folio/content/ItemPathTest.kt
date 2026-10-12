package io.github.big_sw_little_sw.folio.content

import kotlin.test.*

class ItemPathTest {
    @Test
    fun `a relative path in normal form is valid`() {
        val path = ItemPath("kafka/client.properties")

        assertEquals(listOf("kafka", "client.properties"), path.segments)
        assertEquals("client.properties", path.name)
        listOf("a", ".hidden", "a..b/c", "...", "a b/ü.yaml", "a:b").forEach { ItemPath(it) }
    }

    @Test
    fun `Folio rejects a path that is not in normal form and never rewrites it`() {
        listOf("", "/a", "a/", "a//b", ".", "..", "./a", "a/./b", "../a", "a/../b", "a\\b", "..\\a", "a\u0000b")
            .forEach { text -> assertFailsWith<ContentException.InvalidItemPath>(text) { ItemPath(text) } }
    }
}
