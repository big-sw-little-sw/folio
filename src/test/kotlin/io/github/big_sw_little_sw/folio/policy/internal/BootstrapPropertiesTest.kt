package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.policy.InvalidSubjectException
import io.github.big_sw_little_sw.folio.policy.Subject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BootstrapPropertiesTest {
    @Test
    fun `accepts users, groups and applications`() {
        val properties = BootstrapProperties(listOf("user:alice", "group:admins", "application:ops"))

        assertEquals(
            setOf(Subject.User("alice"), Subject.Group("admins"), Subject.Application("ops")),
            properties.adminSubjects,
        )
    }

    @Test
    fun `rejects public and authenticated, which would make everyone an admin`() {
        assertFailsWith<IllegalArgumentException> { BootstrapProperties(listOf("public")) }
        assertFailsWith<IllegalArgumentException> { BootstrapProperties(listOf("user:alice", "authenticated")) }
    }

    @Test
    fun `rejects malformed entries`() {
        assertFailsWith<InvalidSubjectException> { BootstrapProperties(listOf("alice")) }
    }
}
