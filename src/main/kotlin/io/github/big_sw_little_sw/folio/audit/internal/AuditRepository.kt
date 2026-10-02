package io.github.big_sw_little_sw.folio.audit.internal

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper

/** The `audit_event` table. Records are only inserted; v1 has no reads and no pruning (ADR 0038). */
@Repository
class AuditRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    /** Inserts at the database clock's transaction time. */
    fun insert(
        actor: Actor,
        entry: AuditEntry,
    ) {
        val caller = actor as? Actor.Caller
        jdbc
            .sql(
                """
                insert into audit_event (
                    occurred_at, actor_type, actor_subject, actor_application_id, actor_super_admin,
                    action, resource_type, resource_id, resource_path, details
                ) values (
                    now(), :actorType, :subject, :applicationId, :superAdmin,
                    :action, :resourceType, :resourceId, :path, cast(:details as jsonb)
                )
                """.trimIndent(),
            ).param("actorType", actorType(actor))
            .param("subject", caller?.subject)
            .param("applicationId", caller?.applicationId)
            .param("superAdmin", caller?.superAdmin ?: false)
            .param("action", entry.action.name)
            .param("resourceType", entry.resourceType.name)
            .param("resourceId", entry.resourceId)
            .param("path", entry.path)
            .param("details", json.writeValueAsString(entry.details))
            .update()
    }

    private fun actorType(actor: Actor) =
        when (actor) {
            Actor.System -> "SYSTEM"
            Actor.Anonymous -> "ANONYMOUS"
            is Actor.Caller -> "AUTHENTICATED"
        }
}
