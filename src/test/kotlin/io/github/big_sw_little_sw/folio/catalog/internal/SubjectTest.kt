package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.identity.Caller
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The subject format and how subjects match callers (design 7.2). */
class SubjectTest {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), "cli")

    @ParameterizedTest
    @ValueSource(strings = ["public", "authenticated", "user:alice", "group:team a", "application:0f1e-2d3c"])
    fun `the text form parses and formats the same`(text: String) {
        assertEquals(text, Subject.parse(text).toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "everyone", "Public", "user:", "user: ", "group", "role:admin", "user:a\nb"])
    fun `other forms are not valid`(text: String) {
        assertFailsWith<CatalogException.InvalidSubject> { Subject.parse(text) }
    }

    @Test
    fun `an ID has 500 characters or fewer`() {
        Subject.parse("user:" + "x".repeat(500))
        assertFailsWith<CatalogException.InvalidSubject> { Subject.parse("user:" + "x".repeat(501)) }
    }

    @Test
    fun `public matches all callers, also anonymous callers, but not system work`() {
        assertTrue(Subject.Public.matches(alice))
        assertTrue(Subject.Public.matches(Caller.Anonymous))
        assertFalse(Subject.Public.matches(Caller.System))
    }

    @Test
    fun `authenticated matches all callers with a token`() {
        assertTrue(Subject.Authenticated.matches(alice))
        assertFalse(Subject.Authenticated.matches(Caller.Anonymous))
        assertFalse(Subject.Authenticated.matches(Caller.System))
    }

    @Test
    fun `user, group and application match the subject, a group and the application claim`() {
        assertTrue(Subject.parse("user:alice").matches(alice))
        assertTrue(Subject.parse("group:team-a").matches(alice))
        assertTrue(Subject.parse("application:cli").matches(alice))
        assertFalse(Subject.parse("user:bob").matches(alice))
        assertFalse(Subject.parse("group:team-b").matches(alice))
        assertFalse(Subject.parse("application:other").matches(alice))
        assertFalse(Subject.parse("user:Alice").matches(alice), "IDs are exact")
    }
}
