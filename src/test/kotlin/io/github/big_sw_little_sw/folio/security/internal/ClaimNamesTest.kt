package io.github.big_sw_little_sw.folio.security.internal

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaimNamesTest {
    @Test
    fun `maps the default claims to subject, groups and application ID`() {
        val claims = mapOf("sub" to "alice", "groups" to listOf("a", "b"), "azp" to "ci-app", "oid" to "ignored")

        assertEquals(
            ApplicationPrincipal.Authenticated("alice", setOf("a", "b"), "ci-app"),
            ClaimNames().toPrincipal(claims),
        )
    }

    @Test
    fun `reads configured claim names, for example Entra ID's oid`() {
        val names = ClaimNames(subject = "oid", groups = "roles", applicationId = "appid")
        val claims = mapOf("sub" to "pairwise", "oid" to "object-id", "roles" to listOf("r"), "appid" to "app")

        assertEquals(ApplicationPrincipal.Authenticated("object-id", setOf("r"), "app"), names.toPrincipal(claims))
    }

    @Test
    fun `missing groups and application ID map to none`() {
        assertEquals(
            ApplicationPrincipal.Authenticated("alice", emptySet(), null),
            ClaimNames().toPrincipal(mapOf("sub" to "alice")),
        )
    }

    @Test
    fun `rejects a missing or blank subject`() {
        assertFailsWith<IllegalArgumentException> { ClaimNames().toPrincipal(mapOf("groups" to listOf("a"))) }
        assertFailsWith<IllegalArgumentException> { ClaimNames().toPrincipal(mapOf("sub" to " ")) }
    }

    @Test
    fun `rejects claims of the wrong type`() {
        assertFailsWith<IllegalArgumentException> { ClaimNames().toPrincipal(mapOf("sub" to "a", "groups" to "g")) }
        assertFailsWith<IllegalArgumentException> {
            ClaimNames().toPrincipal(mapOf("sub" to "a", "groups" to listOf(1)))
        }
        assertFailsWith<IllegalArgumentException> { ClaimNames().toPrincipal(mapOf("sub" to "a", "azp" to 1)) }
    }

    @Test
    fun `rejects blank claim names`() {
        assertFailsWith<IllegalArgumentException> { ClaimNames(subject = "") }
    }
}
