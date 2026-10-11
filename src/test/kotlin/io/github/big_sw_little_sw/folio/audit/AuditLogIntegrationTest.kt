package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Test classes share the table, so each test reads only the records of its own resource ID. That read also shows that
 * the record keeps the resource ID.
 */
@FolioIntegrationTest
class AuditLogIntegrationTest(
    @Autowired private val auditLog: AuditLog,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
) {
    private val resourceId = UUID.randomUUID()

    @Test
    fun `a record keeps the actor, the resource, the paths and the details`() {
        val entry =
            AuditEntry(
                caller = Caller.Authenticated("alice", setOf("secret-group"), "cli"),
                superAdmin = true,
                operation = "NAMESPACE_MOVED",
                resourceType = "NAMESPACE",
                resourceId = resourceId,
                path = "engineering/search",
                previousPath = "search",
                details = mapOf("parentId" to "ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e"),
            )

        transactions.executeWithoutResult { auditLog.record(entry) }
        val row = records().single()

        assertEquals(
            mapOf(
                "actor_type" to "AUTHENTICATED",
                "actor_subject" to "alice",
                "actor_application_id" to "cli",
                "actor_super_admin" to "true",
                "operation" to "NAMESPACE_MOVED",
                "resource_type" to "NAMESPACE",
                "path" to "engineering/search",
                "previous_path" to "search",
                "details" to """{"parentId": "ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e"}""",
            ),
            row.filterKeys { it !in setOf("id", "occurred_at", "resource_id") }.mapValues { it.value?.toString() },
        )
        assertTrue(row.values.none { it.toString().contains("secret-group") }, "Folio never stores groups")
    }

    @Test
    fun `the database gives the ID and the time`() {
        val databaseTime =
            transactions.execute {
                auditLog.record(entry(Caller.System))
                jdbc.sql("select now()").query().singleValue()
            }

        val row = records().single()
        assertEquals(databaseTime, row["occurred_at"], "The time comes from the database clock")
        assertEquals(UUID_VERSION_7, (row["id"] as UUID).version())
    }

    @Test
    fun `system and anonymous actors have no subject`() {
        transactions.executeWithoutResult {
            auditLog.record(entry(Caller.System))
            auditLog.record(entry(Caller.Anonymous))
        }

        assertEquals(
            listOf(listOf("ANONYMOUS", null, false), listOf("SYSTEM", null, false)),
            records().map { listOf(it["actor_type"], it["actor_subject"], it["actor_super_admin"]) },
        )
    }

    @Test
    fun `a record needs the transaction of its operation`() {
        assertFailsWith<IllegalTransactionStateException> { auditLog.record(entry(Caller.System)) }
    }

    @Test
    fun `a record rolls back with its operation`() {
        transactions.executeWithoutResult { status ->
            auditLog.record(entry(Caller.System))
            status.setRollbackOnly()
        }

        assertEquals(0, records().size)
    }

    @Test
    fun `a record without a resource ID or a path is kept`() {
        val marker = UUID.randomUUID().toString()
        val entry =
            AuditEntry(
                caller = Caller.Authenticated("alice", emptySet(), null),
                superAdmin = true,
                operation = "MASTER_KEYS_REENCRYPTED",
                resourceType = "MASTER_KEY_RING",
                resourceId = null,
                path = null,
                details = mapOf("marker" to marker, "keysByVersion" to mapOf("2" to 3)),
            )

        transactions.executeWithoutResult { auditLog.record(entry) }

        val operations =
            jdbc
                .sql("select operation from audit_event where resource_id is null and details->>'marker' = :marker")
                .param("marker", marker)
                .query(String::class.java)
                .list()
        assertEquals(listOf("MASTER_KEYS_REENCRYPTED"), operations)
    }

    private fun entry(caller: Caller) = AuditEntry(caller, false, "SYNC_FAILED", "CONFIG_SET", resourceId, "a/b")

    private fun records(): List<Map<String, Any?>> = jdbc
        .sql("select * from audit_event where resource_id = :resourceId order by actor_type")
        .param("resourceId", resourceId)
        .query()
        .listOfRows()

    private companion object {
        const val UUID_VERSION_7 = 7
    }
}
