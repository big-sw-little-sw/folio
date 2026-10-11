package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.identity.Caller
import java.util.UUID

/**
 * One audited operation (design 14). The module that does the operation names it, so a new audited operation changes
 * that module only. A record never contains groups, keys, secrets, tokens, transport output or file content. Thus
 * [details] must not contain them either.
 */
data class AuditEntry(
    val caller: Caller,
    /** True if the caller is a super admin. The catalog decides this. */
    val superAdmin: Boolean,
    /** For example `NAMESPACE_RENAMED`. */
    val operation: String,
    /** For example `NAMESPACE` or `CREDENTIAL`. */
    val resourceType: String,
    /** Null only for a resource without an ID, for example the master-key ring. */
    val resourceId: UUID?,
    /** The path after the operation, or before it for a delete. Null for a resource without a path. */
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
        require(!superAdmin || caller is Caller.Authenticated) { "Only an authenticated caller can be a super admin" }
    }

    private companion object {
        val NAME = Regex("[A-Z]+(_[A-Z]+)*")
    }
}
