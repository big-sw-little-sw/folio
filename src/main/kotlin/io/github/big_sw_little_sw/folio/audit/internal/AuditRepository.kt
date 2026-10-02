package io.github.big_sw_little_sw.folio.audit.internal

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** The `audit_event` table. Records are inserted and pruned, never read or changed (ADR 0038, ADR 0041). */
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

    /**
     * Deletes up to [limit] records that occurred more than [retention] ago by the database clock, oldest first, and
     * returns how many. Rows another instance's pruning is deleting are skipped, not waited for.
     */
    fun deleteOlderThan(
        retention: Duration,
        limit: Int,
    ): Int =
        jdbc
            .sql(
                """
                delete from audit_event where id in (
                    select id from audit_event
                    where occurred_at < now() - :retentionMillis * interval '1 millisecond'
                    order by occurred_at
                    limit :limit
                    for update skip locked
                )
                """.trimIndent(),
            ).param("retentionMillis", retention.toMillis())
            .param("limit", limit)
            .update()

    private fun actorType(actor: Actor) =
        when (actor) {
            Actor.System -> "SYSTEM"
            Actor.Anonymous -> "ANONYMOUS"
            is Actor.Caller -> "AUTHENTICATED"
        }
}
