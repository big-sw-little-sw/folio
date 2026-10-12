package io.github.big_sw_little_sw.folio.content

import io.github.big_sw_little_sw.folio.content.internal.RevisionSelector
import kotlin.test.*

class RevisionIdTest {
    @Test
    fun `a revision ID has 1 to 64 lowercase letters and digits`() {
        listOf("a".repeat(40), "17", "z".repeat(64), "0192f0c1e8a47c3b").forEach { RevisionId(it) }
    }

    @Test
    fun `other text is not a revision ID`() {
        listOf("", "A".repeat(40), "a".repeat(65), "abc-def", "HEAD~1", "a b", "latest").forEach { text ->
            assertFailsWith<ContentException.InvalidRevision>(text) { RevisionId(text) }
        }
    }

    @Test
    fun `the selector is latest or an exact revision`() {
        assertEquals(RevisionSelector.Latest, RevisionSelector.parse("latest"))
        assertEquals(RevisionSelector.Exact(RevisionId("a".repeat(40))), RevisionSelector.parse("a".repeat(40)))
        assertFailsWith<ContentException.InvalidRevision> { RevisionSelector.parse("LATEST") }
        assertFailsWith<ContentException.InvalidRevision> { RevisionSelector.parse("refs/heads/main") }
    }
}
