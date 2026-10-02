package io.github.big_sw_little_sw.folio.policy.internal

import org.springframework.mock.env.MockEnvironment
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class RenamedSuperAdminsCheckTest {
    @Test
    fun `fails naming the new key when the old key is set in any form`() {
        listOf("folio.bootstrap.admins" to "user:alice", "folio.bootstrap.admins[0]" to "user:alice").forEach {
            val environment = MockEnvironment().withProperty(it.first, it.second)

            val failure = assertFailsWith<IllegalStateException> { RenamedSuperAdminsCheck(environment) }

            assertContains(failure.message.orEmpty(), "folio.super-admins")
        }
    }

    @Test
    fun `passes when only the new key is set`() {
        RenamedSuperAdminsCheck(MockEnvironment().withProperty("folio.super-admins[0]", "user:alice"))
    }
}
