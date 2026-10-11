package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CallerClaimsTest {
    private val defaults = callerClaims(IdentityProperties.ClaimNames())

    @Test
    fun `maps the default claims to a caller`() {
        val claims = mapOf("sub" to "alice", "groups" to listOf("team-a", "team-b"), "azp" to "cli")

        assertEquals(Caller.Authenticated("alice", setOf("team-a", "team-b"), "cli"), defaults.toCaller(claims))
    }

    @Test
    fun `groups and the application ID are optional`() {
        assertEquals(Caller.Authenticated("alice", emptySet(), null), defaults.toCaller(mapOf("sub" to "alice")))
    }

    @Test
    fun `uses the configured claim names`() {
        val entra =
            callerClaims(IdentityProperties.ClaimNames(subject = "oid", groups = "roles", applicationId = "appid"))
        val claims = mapOf("sub" to "pairwise", "oid" to "object-id", "roles" to listOf("g"), "appid" to "app")

        assertEquals(Caller.Authenticated("object-id", setOf("g"), "app"), entra.toCaller(claims))
    }

    @Test
    fun `claims without a usable subject or with wrong types give no caller`() {
        listOf(
            mapOf("groups" to listOf("g")),
            mapOf("sub" to " "),
            mapOf("sub" to 42),
            mapOf("sub" to "alice", "groups" to "g"),
            mapOf("sub" to "alice", "groups" to listOf("g", 1)),
            mapOf("sub" to "alice", "azp" to listOf("cli")),
        ).forEach { claims ->
            assertFailsWith<InvalidClaims>(claims.toString()) { defaults.toCaller(claims) }
        }
    }

    @Test
    fun `claim names must not be blank`() {
        assertFailsWith<IllegalArgumentException> { IdentityProperties.ClaimNames(subject = "") }
    }

    private fun callerClaims(names: IdentityProperties.ClaimNames) = CallerClaims(names, TokenDirectory(names.groups))
}
