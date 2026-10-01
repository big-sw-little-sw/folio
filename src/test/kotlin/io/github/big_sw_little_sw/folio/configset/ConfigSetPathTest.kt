package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.namespace.Slug
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigSetPathTest {
    @Test
    fun `the last segment is the ConfigSet and the others its namespaces`() {
        val path = ConfigSetPath.parse("engineering/ai/service-a")

        assertEquals(ConfigSetPath(listOf(Slug("engineering"), Slug("ai")), Slug("service-a")), path)
        assertEquals("engineering/ai/service-a", path.toString())
    }

    @Test
    fun `a ConfigSet in a root namespace has two segments`() {
        assertEquals(
            ConfigSetPath(listOf(Slug("engineering")), Slug("service-a")),
            ConfigSetPath.parse("engineering/service-a"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "service-a",
            "/engineering/service-a",
            "engineering/service-a/",
            "engineering//service-a",
            "engineering/Service-A",
            "engineering/service_a",
            "engineering/../service-a",
            "engineering\\service-a",
            " engineering/service-a",
        ],
    )
    fun `rejects fewer than two segments, empty segments and invalid slugs`(text: String) {
        val failure = assertFailsWith<InvalidConfigSetPathException> { ConfigSetPath.parse(text) }
        assertEquals(text, failure.path)
    }

    @Test
    fun `a path without namespaces cannot be built`() {
        assertFailsWith<IllegalArgumentException> { ConfigSetPath(emptyList(), Slug("service-a")) }
    }
}
