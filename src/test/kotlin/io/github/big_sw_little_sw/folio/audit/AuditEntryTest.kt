package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.identity.Caller
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditEntryTest {
    private val alice = Caller.Authenticated("alice", emptySet(), null)

    @Test
    fun `an entry names its operation and resource type in uppercase words`() {
        val entry = entry(alice, "NAMESPACE_RENAMED", "CONFIG_SET")

        assertEquals("NAMESPACE_RENAMED", entry.operation)
        assertEquals("CONFIG_SET", entry.resourceType)
    }

    @Test
    fun `other names are not valid`() {
        listOf("namespace_renamed", "NAMESPACE-RENAMED", "NAMESPACE__RENAMED", "_NAMESPACE", "").forEach { operation ->
            assertFailsWith<IllegalArgumentException>(operation) { entry(alice, operation, "NAMESPACE") }
        }
        assertFailsWith<IllegalArgumentException> { entry(alice, "NAMESPACE_RENAMED", "Namespace") }
    }

    @Test
    fun `only an authenticated caller can be a super admin`() {
        entry(alice, "NAMESPACE_RENAMED", "NAMESPACE", superAdmin = true)
        listOf(Caller.System, Caller.Anonymous).forEach { caller ->
            assertFailsWith<IllegalArgumentException>(caller.toString()) {
                entry(caller, "NAMESPACE_RENAMED", "NAMESPACE", superAdmin = true)
            }
        }
    }

    private fun entry(caller: Caller, operation: String, resourceType: String, superAdmin: Boolean = false) =
        AuditEntry(caller, superAdmin, operation, resourceType, UUID.randomUUID(), "a/b")
}
