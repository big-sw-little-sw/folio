package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.audit.AuditEntry
import io.github.big_sw_little_sw.folio.audit.AuditLog
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component

/**
 * Writes the audit records of the catalog (design 14). Callers call it only for operations that changed something.
 * [edited] then writes one record for each field that changed.
 *
 * The resource type is `NAMESPACE` or `CONFIG_SET`, and operations start with it, for example `NAMESPACE_RENAMED`.
 * Rule operations are `RULE_PUT` and `RULE_DELETED`, with the type, the ID and the path of the target of the rule.
 */
@Component
class CatalogAudit(private val auditLog: AuditLog, private val authorizer: Authorizer) {
    fun created(caller: Caller, resource: ResourceId, path: Path, details: Map<String, Any?>) =
        record(caller, "${typeOf(resource)}_CREATED", resource, path, details = details)

    /**
     * One record for each change, so that a PATCH of the slug and the description writes a rename and a describe.
     * Thus a query for one operation finds all of them.
     */
    fun edited(
        caller: Caller,
        resource: ResourceId,
        before: Ancestry,
        slug: Slug,
        previousDescription: String?,
        description: String?,
    ) {
        val previousSlug = before.links.last().slug
        val path = before.renamed(slug)
        if (slug != previousSlug) {
            val details = mapOf("slug" to slug.value, "previousSlug" to previousSlug.value)
            record(caller, "${typeOf(resource)}_RENAMED", resource, path, before.path, details)
        }
        if (description != previousDescription) {
            val details = mapOf("description" to description, "previousDescription" to previousDescription)
            record(caller, "${typeOf(resource)}_DESCRIBED", resource, path, details = details)
        }
    }

    fun moved(caller: Caller, resource: ResourceId, path: Path, previousPath: Path, details: Map<String, Any?>) =
        record(caller, "${typeOf(resource)}_MOVED", resource, path, previousPath, details)

    fun deleted(caller: Caller, resource: ResourceId, path: Path) =
        record(caller, "${typeOf(resource)}_DELETED", resource, path)

    fun rulePut(caller: Caller, resource: ResourceId, path: Path, rule: Rule, previous: Rule?) = record(
        caller,
        "RULE_PUT",
        resource,
        path,
        details =
        mapOf(
            "action" to rule.action.name,
            "subjects" to rule.subjects.map { it.toString() },
            "previousSubjects" to previous?.subjects?.map { it.toString() },
        ),
    )

    fun ruleDeleted(caller: Caller, resource: ResourceId, path: Path, previous: Rule) = record(
        caller,
        "RULE_DELETED",
        resource,
        path,
        details =
        mapOf(
            "action" to previous.action.name,
            "previousSubjects" to previous.subjects.map { it.toString() },
        ),
    )

    private fun record(
        caller: Caller,
        operation: String,
        resource: ResourceId,
        path: Path,
        previousPath: Path? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        auditLog.record(
            AuditEntry(
                caller = caller,
                superAdmin = authorizer.isSuperAdmin(caller),
                operation = operation,
                resourceType = typeOf(resource),
                resourceId = resource.value,
                path = path.toString(),
                previousPath = previousPath?.toString(),
                details = details,
            ),
        )
    }

    private fun typeOf(resource: ResourceId) = when (resource) {
        is NamespaceId -> "NAMESPACE"
        is ConfigSetId -> "CONFIG_SET"
    }
}
