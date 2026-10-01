package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubjectTest {
    private val alice = ApplicationPrincipal.Authenticated("alice", setOf("editors"), "ci-app")
    private val anonymous = ApplicationPrincipal.Anonymous

    @Test
    fun `parses every subject type and formats it back to the same text`() {
        val subjects =
            mapOf(
                "public" to Subject.Public,
                "authenticated" to Subject.Authenticated,
                "user:alice" to Subject.User("alice"),
                "group:editors" to Subject.Group("editors"),
                "application:ci-app" to Subject.Application("ci-app"),
                "group:cn=a:b" to Subject.Group("cn=a:b"),
            )

        subjects.forEach { (text, subject) ->
            assertEquals(subject, Subject.parse(text))
            assertEquals(text, subject.toString())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "user", "user:", "user: ", "role:x", "Public", "public:x", "everyone"])
    fun `rejects unknown types and missing IDs`(text: String) {
        val failure = assertFailsWith<InvalidSubjectException> { Subject.parse(text) }
        assertEquals(text, failure.subject)
    }

    @Test
    fun `rejects IDs longer than the column`() {
        Subject.parse("user:" + "a".repeat(Subject.MAX_ID_LENGTH))
        assertFailsWith<InvalidSubjectException> { Subject.parse("user:" + "a".repeat(Subject.MAX_ID_LENGTH + 1)) }
    }

    @Test
    fun `public matches everyone, authenticated only callers with a token`() {
        assertTrue(Subject.Public.matches(anonymous))
        assertTrue(Subject.Public.matches(alice))
        assertFalse(Subject.Authenticated.matches(anonymous))
        assertTrue(Subject.Authenticated.matches(alice))
    }

    @Test
    fun `user, group and application match the principal's subject, groups and application ID`() {
        assertTrue(Subject.User("alice").matches(alice))
        assertFalse(Subject.User("bob").matches(alice))
        assertTrue(Subject.Group("editors").matches(alice))
        assertFalse(Subject.Group("admins").matches(alice))
        assertTrue(Subject.Application("ci-app").matches(alice))
        assertFalse(Subject.Application("other").matches(alice))
        assertFalse(Subject.User("alice").matches(anonymous))
    }

    @Test
    fun `a user subject does not match a group or application with the same ID`() {
        val principal = ApplicationPrincipal.Authenticated("x", setOf("y"), "z")

        assertFalse(Subject.User("y").matches(principal))
        assertFalse(Subject.Group("x").matches(principal))
        assertFalse(Subject.Application("x").matches(principal))
    }
}
