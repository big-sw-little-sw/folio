package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.identity.Caller
import java.util.UUID

/**
 * One audited operation. The module that does the operation names it, so a new audited operation changes only that
 * module. [details] must never contain groups, keys, secrets, tokens, transport output or file content.
 */
data class AuditEntry(
    /** The record takes the actor and the super-admin status of the actor from this caller. */
    val caller: Caller,
    /** For example `NAMESPACE_RENAMED`. */
    val operation: String,
    /** The type of the resource, for example `NAMESPACE` or `CREDENTIAL`. */
    val resourceType: String,
    /** The ID is null only for a resource without an ID, for example the master-key ring. */
    val resourceId: UUID?,
    /** The path after the operation, or the path before a delete. The path is null for a resource without a path. */
    val path: String?,
    /** The path before a rename or a move. */
    val previousPath: String? = null,
    val details: Map<String, Any?> = emptyMap(),
) {
    init {
        require(NAME.matches(operation)) {
            "An audit operation is uppercase words joined by underscores: $operation"
        }
        require(
            NAME.matches(resourceType),
        ) { "A resource type is uppercase words joined by underscores: $resourceType" }
    }

    private companion object {
        val NAME = Regex("[A-Z]+(_[A-Z]+)*")
    }
}
