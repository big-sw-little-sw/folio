package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

/** Writes audit records. Folio never changes a record, and retention deletes it after `folio.audit.retention`. */
@Service
class AuditLog(private val jdbc: JdbcClient, private val json: JsonMapper) {
    /**
     * Writes the record in the transaction of the operation. Thus the record commits with its operation, and a failed
     * insert rolls the operation back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(entry: AuditEntry) {
        val authenticated = entry.caller as? Caller.Authenticated
        jdbc
            .sql(
                """
                insert into audit_event (
                    actor_type, actor_subject, actor_application_id, actor_super_admin,
                    operation, resource_type, resource_id, path, previous_path, details
                ) values (
                    :actorType, :subject, :applicationId, :superAdmin,
                    :operation, :resourceType, :resourceId, :path, :previousPath, cast(:details as jsonb)
                )
                """.trimIndent(),
            ).param("actorType", actorType(entry.caller))
            .param("subject", authenticated?.subject)
            .param("applicationId", authenticated?.applicationId)
            .param("superAdmin", entry.superAdmin)
            .param("operation", entry.operation)
            .param("resourceType", entry.resourceType)
            .param("resourceId", entry.resourceId)
            .param("path", entry.path)
            .param("previousPath", entry.previousPath)
            .param("details", json.writeValueAsString(entry.details))
            .update()
    }

    private fun actorType(caller: Caller) = when (caller) {
        is Caller.Authenticated -> "AUTHENTICATED"
        Caller.Anonymous -> "ANONYMOUS"
        Caller.System -> "SYSTEM"
    }
}
