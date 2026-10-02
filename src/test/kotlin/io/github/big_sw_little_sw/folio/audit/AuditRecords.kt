package io.github.big_sw_little_sw.folio.audit

import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.util.UUID

/** An `audit_event` row as tests read it; [details] is the parsed JSON object. */
data class AuditRow(
    val actorType: String,
    val actorSubject: String?,
    val actorApplicationId: String?,
    val actorSuperAdmin: Boolean,
    val action: String,
    val resourceType: String,
    val resourceId: UUID?,
    val path: String?,
    val details: Map<String, Any?>,
)

/** Reads audit records. Test classes share one database and never clear the table, so reads filter by resource. */
class AuditRecords(
    private val jdbc: JdbcClient,
) {
    private val json = JsonMapper.builder().build()

    /** The records of [resourceId], oldest first. */
    fun of(resourceId: UUID): List<AuditRow> =
        jdbc
            .sql("select * from audit_event where resource_id = :id order by occurred_at, id")
            .param("id", resourceId)
            .query { rs, _ -> rs.toRow() }
            .list()

    /** The newest records of the master-key ring, which has no ID, newest first. */
    fun ofMasterKeyRing(limit: Int): List<AuditRow> =
        jdbc
            .sql(
                "select * from audit_event where resource_type = 'MASTER_KEY_RING' " +
                    "order by occurred_at desc, id desc limit :limit",
            ).param("limit", limit)
            .query { rs, _ -> rs.toRow() }
            .list()

    private fun ResultSet.toRow() =
        AuditRow(
            actorType = getString("actor_type"),
            actorSubject = getString("actor_subject"),
            actorApplicationId = getString("actor_application_id"),
            actorSuperAdmin = getBoolean("actor_super_admin"),
            action = getString("action"),
            resourceType = getString("resource_type"),
            resourceId = getObject("resource_id", UUID::class.java),
            path = getString("resource_path"),
            details = json.readValue(getString("details")),
        )
}
