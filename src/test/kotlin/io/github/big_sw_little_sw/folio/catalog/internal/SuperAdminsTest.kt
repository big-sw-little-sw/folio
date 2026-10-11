package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `folio.super-admins` is validated at startup (design 7.2). */
class SuperAdminsTest {
    @Test
    fun `entries use the subject format`() {
        val superAdmins = SuperAdmins(listOf("group:folio-admins", "user:root", "application:ops"))

        assertEquals(
            Subject.Group("folio-admins"),
            superAdmins.match(Caller.Authenticated("x", setOf("folio-admins"), null)),
        )
        assertNull(superAdmins.match(Caller.Anonymous))
        assertNull(superAdmins.match(Caller.System))
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
        assertEquals(emptySet(), SuperAdmins().entries)
    }
}
