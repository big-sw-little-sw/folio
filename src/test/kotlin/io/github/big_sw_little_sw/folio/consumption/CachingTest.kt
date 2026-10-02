package io.github.big_sw_little_sw.folio.consumption

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CachingTest {
    private val exact = RevisionSelector.Exact("a".repeat(40))
    private val latestMaxAge = Duration.ofSeconds(30)

    @Test
    fun `exact revisions are immutable for a year, shared only when public`() {
        assertEquals(
            "public, max-age=31536000, immutable",
            CachePolicy.of(exact, shared = true, latestMaxAge).headerValue,
        )
        assertEquals(
            "private, max-age=31536000, immutable",
            CachePolicy.of(exact, shared = false, latestMaxAge).headerValue,
        )
    }

    @Test
    fun `latest is cached for the configured time, shared only when public`() {
        assertEquals("public, max-age=30", CachePolicy.of(RevisionSelector.Latest, true, latestMaxAge).headerValue)
        assertEquals("private, max-age=30", CachePolicy.of(RevisionSelector.Latest, false, latestMaxAge).headerValue)
        assertEquals("private, max-age=0", CachePolicy.of(RevisionSelector.Latest, false, Duration.ZERO).headerValue)
    }

    @Test
    fun `digest tags are stable and differ when any part differs`() {
        val tag = digestTag("production/app", "a".repeat(40))

        assertEquals(tag, digestTag("production/app", "a".repeat(40)))
        assertEquals(32, tag.length)
        assertNotEquals(tag, digestTag("production/other", "a".repeat(40)))
        assertNotEquals(tag, digestTag("production/app", "b".repeat(40)))
        assertNotEquals(digestTag("production/app", ""), digestTag("production/app"))
    }
}
