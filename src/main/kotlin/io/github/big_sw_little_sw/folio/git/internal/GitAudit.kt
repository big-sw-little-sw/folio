package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.audit.AuditEntry
import io.github.big_sw_little_sw.folio.audit.AuditLog
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component

/**
 * Writes the audit records of the `git` module. Each record has the resource type `GIT_SOURCE`, with the UUID and the
 * path of the ConfigSet.
 */
@Component
class GitAudit(private val auditLog: AuditLog) {
    fun record(caller: Caller, configSet: ConfigSet, operation: String, details: Map<String, Any?> = emptyMap()) =
        auditLog.record(
            AuditEntry(
                caller = caller,
                operation = operation,
                resourceType = "GIT_SOURCE",
                resourceId = configSet.id.value,
                path = configSet.path.toString(),
                details = details,
            ),
        )
}
