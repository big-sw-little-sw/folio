package io.github.big_sw_little_sw.folio.catalog

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Slugs and paths (design 5.3). */
class PathTest {
    @ParameterizedTest
    @ValueSource(strings = ["a", "service-a", "a1-b2-c3", "0"])
    fun `a slug has lowercase letters, digits and single hyphens between them`(text: String) {
        assertEquals(text, Slug(text).value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "A", "-a", "a-", "a--b", "a_b", "a.b", "a b", "ä", "a/b"])
    fun `other slugs are not valid`(text: String) {
        assertFailsWith<CatalogException.InvalidSlug> { Slug(text) }
    }

    @Test
    fun `a slug has 100 characters or fewer`() {
        Slug("x".repeat(100))
        assertFailsWith<CatalogException.InvalidSlug> { Slug("x".repeat(101)) }
    }

    @Test
    fun `a path is slugs joined by slashes`() {
        val path = Path.parse("engineering/search/service-a")

        assertEquals(listOf("engineering", "search", "service-a"), path.slugs.map { it.value })
        assertEquals("engineering/search/service-a", path.toString())
        assertEquals(
            "engineering/search/service-b",
            Path.parse("engineering/search").plus(Slug("service-b")).toString(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/", "/a", "a/", "a//b", "a/B", "a/b c", "a/../b", "a/./b"])
    fun `a path has no slash at the ends, no empty segments and only slugs`(text: String) {
        assertFailsWith<CatalogException.InvalidPath> { Path.parse(text) }
    }

    @Test
    fun `API IDs have the prefixes ns and cfg`() {
        val uuid = java.util.UUID.fromString("0192f0c1-e8a4-7c3b-9d2e-5f6a7b8c9d0e")

        assertEquals("ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e", NamespaceId(uuid).toApiId())
        assertEquals("cfg_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e", ConfigSetId(uuid).toApiId())
        assertEquals(ConfigSetId(uuid), CONFIG_SET_ID.parse("cfg_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e"))
    }
}
