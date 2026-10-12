package io.github.big_sw_little_sw.folio.identity

import kotlin.test.*

/** Folio validates `folio.super-admins` at startup, and the identity module applies the setting to each caller. */
class SuperAdminsTest {
    private val superAdmins = SuperAdmins(listOf("group:folio-admins", "user:root", "application:ops"))

    @Test
    fun `a caller that an entry names is a super admin`() {
        assertTrue(superAdmins.caller("x", setOf("folio-admins"), null).superAdmin)
        assertTrue(superAdmins.caller("root", emptySet(), null).superAdmin)
        assertTrue(superAdmins.caller("x", emptySet(), "ops").superAdmin)
        assertFalse(superAdmins.caller("x", setOf("team-a"), "cli").superAdmin)
    }

    @Test
    fun `the caller keeps its values`() {
        assertEquals(
            Caller.Authenticated("root", setOf("team-a"), "cli", superAdmin = true),
            superAdmins.caller("root", setOf("team-a"), "cli"),
        )
    }

    @Test
    fun `an explanation gets the first entry that names the caller`() {
        val caller = superAdmins.caller("root", setOf("folio-admins"), null)

        assertEquals(Subject.Group("folio-admins"), superAdmins.entryFor(caller))
        assertNull(superAdmins.entryFor(Caller.Authenticated("x", emptySet(), null)))
        assertNull(superAdmins.entryFor(Caller.Anonymous))
        assertNull(superAdmins.entryFor(Caller.System))
    }

    @Test
    fun `anonymous callers and system work are never super admins`() {
        assertFalse(Caller.Anonymous.superAdmin)
        assertFalse(Caller.System.superAdmin)
    }

    @Test
    fun `an entry that is not a subject stops the start, and the message names its index only`() {
        val failure = assertFailsWith<IllegalArgumentException> { SuperAdmins(listOf("group:a", "admins")) }

        assertEquals("folio.super-admins[1] is not user:, group: or application:", failure.message)
    }

    @Test
    fun `public and authenticated are not permitted`() {
        assertFailsWith<IllegalArgumentException> { SuperAdmins(listOf("public")) }
        assertFailsWith<IllegalArgumentException> { SuperAdmins(listOf("authenticated")) }
    }

    @Test
    fun `no super admins is the default`() {
        assertFalse(SuperAdmins().caller("root", setOf("folio-admins"), "ops").superAdmin)
    }
}
