package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.content.RevisionId
import java.time.Duration
import kotlin.test.*

class CachingTest {
    private val exact = RevisionSelector.Exact(RevisionId("a".repeat(40)))
    private val latestMaxAge = Duration.ofSeconds(30)

    @Test
    fun `an exact revision is immutable for a year, and shared caches revalidate a public one sooner`() {
        assertEquals(
            "public, max-age=31536000, s-maxage=30, immutable",
            cacheControl(exact, public = true, latestMaxAge),
        )
        assertEquals("private, max-age=31536000, immutable", cacheControl(exact, public = false, latestMaxAge))
    }

    @Test
    fun `latest uses the latest max age, and it is public only for a public ConfigSet`() {
        assertEquals("public, max-age=30", cacheControl(RevisionSelector.Latest, true, latestMaxAge))
        assertEquals("private, max-age=30", cacheControl(RevisionSelector.Latest, false, latestMaxAge))
        assertEquals("private, max-age=0", cacheControl(RevisionSelector.Latest, false, Duration.ZERO))
    }

    @Test
    fun `a digest tag is stable and changes when a part changes`() {
        val tag = digestTag(listOf("team/app", "a".repeat(40)))

        assertEquals(tag, digestTag(listOf("team/app", "a".repeat(40))))
        assertNotEquals(tag, digestTag(listOf("team/other", "a".repeat(40))))
        assertNotEquals(tag, digestTag(listOf("team/app", "b".repeat(40))))
        assertNotEquals(digestTag(listOf("a", "bc")), digestTag(listOf("ab", "c")))
    }
}
