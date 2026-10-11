package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.identity.Caller
import java.util.UUID
import kotlin.test.*

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

    private fun entry(caller: Caller, operation: String, resourceType: String) =
        AuditEntry(caller, operation, resourceType, UUID.randomUUID(), "a/b")
}
