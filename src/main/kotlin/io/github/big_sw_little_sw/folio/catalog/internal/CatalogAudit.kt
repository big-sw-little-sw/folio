package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.audit.AuditEntry
import io.github.big_sw_little_sw.folio.audit.AuditLog
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.Namespace
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component

/**
 * Writes the audit records of the catalog. The services call this class only for operations that changed something,
 * so a request that changes nothing adds no record. A rule record has the type, the ID and the path of the resource of
 * the rule.
 */
@Component
class CatalogAudit(private val auditLog: AuditLog) {
    fun created(caller: Caller, namespace: Namespace) = record(
        caller,
        "NAMESPACE_CREATED",
        namespace.id,
        namespace.path,
        details = mapOf("parentId" to namespace.parentId?.toApiId(), "description" to namespace.description),
    )

    fun created(caller: Caller, configSet: ConfigSet) = record(
        caller,
        "CONFIG_SET_CREATED",
        configSet.id,
        configSet.path,
        details =
        mapOf(
            "namespaceId" to configSet.namespaceId.toApiId(),
            "sourceType" to configSet.sourceType.name,
            "description" to configSet.description,
        ),
    )

    /**
     * Writes one record for each field that changed. Thus a PATCH of the slug and the description writes a rename
     * record and a describe record. Without separate records, a query for one operation would miss the changes of a
     * combined PATCH.
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

    /** [before] is the ancestry before the move. The record takes the previous path from [before]. */
    fun moved(caller: Caller, before: Ancestry, namespace: Namespace, previousParentId: NamespaceId?) = record(
        caller,
        "NAMESPACE_MOVED",
        namespace.id,
        namespace.path,
        before.path,
        mapOf("parentId" to namespace.parentId?.toApiId(), "previousParentId" to previousParentId?.toApiId()),
    )

    /** [before] is the ancestry before the move. The record takes the previous path from [before]. */
    fun moved(caller: Caller, before: Ancestry, configSet: ConfigSet, previousNamespaceId: NamespaceId) = record(
        caller,
        "CONFIG_SET_MOVED",
        configSet.id,
        configSet.path,
        before.path,
        mapOf("namespaceId" to configSet.namespaceId.toApiId(), "previousNamespaceId" to previousNamespaceId.toApiId()),
    )

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
