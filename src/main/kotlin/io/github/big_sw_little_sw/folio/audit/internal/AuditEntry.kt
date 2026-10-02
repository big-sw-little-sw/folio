package io.github.big_sw_little_sw.folio.audit.internal

import java.util.UUID

/** Stored by name. [bySystem] actions are Folio's own, not a caller's. */
enum class AuditAction(
    val bySystem: Boolean = false,
) {
    NAMESPACE_CREATED,
    NAMESPACE_RENAMED,
    NAMESPACE_MOVED,
    NAMESPACE_DELETED,
    CONFIG_SET_CREATED,
    CONFIG_SET_RENAMED,
    CONFIG_SET_MOVED,
    CONFIG_SET_DELETED,

    /** On the namespace or ConfigSet the rule attaches to. */
    POLICY_RULE_PUT,
    POLICY_RULE_DELETED,
    CREDENTIAL_CREATED,
    CREDENTIAL_KEY_REGENERATED,
    CREDENTIAL_KEY_ACTIVATED,
    CREDENTIAL_KEY_DISCARDED,
    CREDENTIAL_KEY_REPLACED,
    CREDENTIAL_DISABLED,
    MASTER_KEYS_REENCRYPTED,
    SYNC_REQUESTED,
    SYNC_REVISION_CHANGED(bySystem = true),
    SYNC_FAILED(bySystem = true),
    SYNC_RECOVERED(bySystem = true),
}

enum class ResourceType {
    NAMESPACE,
    CONFIG_SET,
    CREDENTIAL,
    MASTER_KEY_RING,
}

/**
 * One audit record without its actor and time. [resourceId] is null for the master-key ring; [path] is a namespace or
 * ConfigSet path at the time, null for other resources. [details] become a JSON object; IDs in them are UUIDs.
 */
data class AuditEntry(
    val action: AuditAction,
    val resourceType: ResourceType,
    val resourceId: UUID?,
    val path: String?,
    val details: Map<String, Any?>,
)

/** Who did it. A [Caller]'s [superAdmin] says whether it is a configured super admin. */
sealed interface Actor {
    data object System : Actor

    data object Anonymous : Actor

    data class Caller(
        val subject: String,
        val applicationId: String?,
        val superAdmin: Boolean,
    ) : Actor
}
