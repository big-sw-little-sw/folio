package io.github.big_sw_little_sw.folio.namespace

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlugTest {
    @ParameterizedTest
    @ValueSource(strings = ["a", "7", "service-a", "engineering", "v2-api-01"])
    fun `accepts lowercase letters, digits and single inner hyphens`(value: String) {
        assertEquals(value, Slug(value).value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Service", "service_a", "service a", "-a", "a-", "a--b", "a/b", "a.b", "ä"])
    fun `rejects other characters, empty slugs and leading, trailing or repeated hyphens`(value: String) {
        val failure = assertFailsWith<InvalidSlugException> { Slug(value) }
        assertEquals(value, failure.slug)
    }

    @Test
    fun `accepts 100 characters and rejects 101`() {
        Slug("a".repeat(100))
        assertFailsWith<InvalidSlugException> { Slug("a".repeat(101)) }
    }
}
