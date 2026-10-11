package io.github.big_sw_little_sw.folio.identity

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** These tests check the subject format and how subjects match callers (design 2.1, 7.2). */
class SubjectTest {
    private val alice = Caller.Authenticated("alice", setOf("team-a", "team b"), "cli")

    @ParameterizedTest
    @ValueSource(strings = ["public", "authenticated", "user:alice", "group:team a", "application:0f1e-2d3c"])
    fun `the text form parses and formats the same`(text: String) {
        assertEquals(text, Subject.parse(text).toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "everyone", "Public", "user:", "user: ", "group", "role:admin", "user:a\nb"])
    fun `other forms are not valid`(text: String) {
        val failure = assertFailsWith<IdentityException.InvalidSubject> { Subject.parse(text) }
        assertEquals("INVALID_SUBJECT", failure.code)
    }

    @Test
    fun `an ID has 500 characters or fewer`() {
        Subject.parse("user:" + "x".repeat(500))
        assertFailsWith<IdentityException.InvalidSubject> { Subject.parse("user:" + "x".repeat(501)) }
    }

    @Test
    fun `public matches all callers, also anonymous callers, but not system work`() {
        assertTrue(Subject.Public.names(alice))
        assertTrue(Subject.Public.names(Caller.Anonymous))
        assertFalse(Subject.Public.names(Caller.System))
    }

    @Test
    fun `authenticated matches all callers with a token`() {
        assertTrue(Subject.Authenticated.names(alice))
        assertFalse(Subject.Authenticated.names(Caller.Anonymous))
        assertFalse(Subject.Authenticated.names(Caller.System))
    }

    @Test
    fun `user, group and application match the subject, a group and the application claim`() {
        assertTrue(Subject.parse("user:alice").names(alice))
        assertTrue(Subject.parse("group:team-a").names(alice))
        assertTrue(Subject.parse("group:team b").names(alice))
        assertTrue(Subject.parse("application:cli").names(alice))
        assertFalse(Subject.parse("user:bob").names(alice))
        assertFalse(Subject.parse("group:team-b").names(alice))
        assertFalse(Subject.parse("application:other").names(alice))
        assertFalse(Subject.parse("user:Alice").names(alice), "IDs are exact")
        assertFalse(Subject.parse("group:alice").names(alice), "The type of a subject counts")
    }

    @Test
    fun `the subjects that name a caller are the ones that a rule query looks for`() {
        assertEquals(
            setOf(
                Subject.Public,
                Subject.Authenticated,
                Subject.User("alice"),
                Subject.Group("team-a"),
                Subject.Group("team b"),
                Subject.Application("cli"),
            ),
            Subject.matching(alice),
        )
        assertEquals(setOf(Subject.User("bob"), Subject.Public, Subject.Authenticated), matchingOf("bob"))
        assertEquals(setOf(Subject.Public), Subject.matching(Caller.Anonymous))
        assertEquals(emptySet(), Subject.matching(Caller.System))
    }

    @Test
    fun `the super-admin status of a caller does not change what it matches`() {
        assertEquals(Subject.matching(alice), Subject.matching(alice.copy(superAdmin = true)))
    }

    private fun matchingOf(subject: String) = Subject.matching(Caller.Authenticated(subject, emptySet(), null))

    private fun Subject.names(caller: Caller) = this in Subject.matching(caller)
}
