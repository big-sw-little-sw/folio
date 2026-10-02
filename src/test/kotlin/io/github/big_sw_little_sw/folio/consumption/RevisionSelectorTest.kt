package io.github.big_sw_little_sw.folio.consumption

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RevisionSelectorTest {
    @Test
    fun `latest and exact commit IDs are selectors`() {
        val commit = "0123456789abcdef0123456789abcdef01234567"

        assertEquals(RevisionSelector.Latest, RevisionSelector.parse("latest"))
        assertEquals(RevisionSelector.Exact(commit), RevisionSelector.parse(commit))
    }

    @Test
    fun `anything else is rejected`() {
        listOf("", "LATEST", "main", "refs/heads/main", "HEAD~1", "abc123", "A".repeat(40), "a".repeat(41)).forEach {
            assertFailsWith<InvalidRevisionException>(it) { RevisionSelector.parse(it) }
        }
    }
}
