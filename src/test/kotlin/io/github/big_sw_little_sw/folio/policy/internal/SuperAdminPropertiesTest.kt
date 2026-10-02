package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.policy.InvalidSubjectException
import io.github.big_sw_little_sw.folio.policy.Subject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SuperAdminPropertiesTest {
    @Test
    fun `accepts users, groups and applications`() {
        val properties = SuperAdminProperties(listOf("user:alice", "group:admins", "application:ops"))

        assertEquals(
            setOf(Subject.User("alice"), Subject.Group("admins"), Subject.Application("ops")),
            properties.subjects,
        )
    }

    @Test
    fun `rejects public and authenticated, which would make everyone an admin`() {
        assertFailsWith<IllegalArgumentException> { SuperAdminProperties(listOf("public")) }
        assertFailsWith<IllegalArgumentException> { SuperAdminProperties(listOf("user:alice", "authenticated")) }
    }

    @Test
    fun `rejects malformed entries`() {
        assertFailsWith<InvalidSubjectException> { SuperAdminProperties(listOf("alice")) }
    }
}
