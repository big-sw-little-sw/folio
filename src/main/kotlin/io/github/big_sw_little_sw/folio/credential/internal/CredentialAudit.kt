package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.audit.AuditEntry
import io.github.big_sw_little_sw.folio.audit.AuditLog
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component

/**
 * Writes the audit records of the credential module (design 14). The services call this class only for operations
 * that changed something.
 *
 * A credential record has the resource type `CREDENTIAL`, the UUID of the credential and the path of the Namespace
 * that owns the credential. Its details name keys by API ID and fingerprint. A record never contains key material.
 * The re-encryption record has no resource UUID and no path.
 */
@Component
class CredentialAudit(private val auditLog: AuditLog) {
    /**
     * The details of a create and a delete give the Namespace ID, the name and the Git instance. Without them, a
     * record of a deleted credential has only its UUID, and a person cannot tell which credential it was.
     */
    fun created(caller: Caller, credential: Credential) =
        record(caller, "CREDENTIAL_CREATED", credential, identity(credential) + keyChanges(null, credential))

    /**
     * Records a key change. The details list each key whose status changed, with its previous status. A new key has
     * no previous status.
     */
    fun keysChanged(caller: Caller, operation: KeyOperation, before: Credential, after: Credential) =
        record(caller, "CREDENTIAL_${operation.name}", after, keyChanges(before, after))

    fun disabled(caller: Caller, credential: Credential) = record(caller, "CREDENTIAL_DISABLED", credential)

    fun deleted(caller: Caller, credential: Credential) =
        record(caller, "CREDENTIAL_DELETED", credential, identity(credential))

    /** One record for each run that re-encrypted keys. [reencrypted] maps each previous version to its key count. */
    fun reencrypted(caller: Caller, activeVersion: Int, reencrypted: Map<Int, Int>, complete: Boolean) =
        auditLog.record(
            AuditEntry(
                caller = caller,
                operation = "MASTER_KEYS_REENCRYPTED",
                resourceType = "MASTER_KEY_RING",
                resourceId = null,
                path = null,
                details =
                mapOf(
                    "activeVersion" to activeVersion,
                    "reencrypted" to reencrypted.toSortedMap(),
                    "complete" to complete,
                ),
            ),
        )

    private fun record(
        caller: Caller,
        operation: String,
        credential: Credential,
        details: Map<String, Any?> = emptyMap(),
    ) = auditLog.record(
        AuditEntry(
            caller = caller,
            operation = operation,
            resourceType = "CREDENTIAL",
            resourceId = credential.id.value,
            path = credential.namespacePath.toString(),
            details = details,
        ),
    )

    private fun identity(credential: Credential) = mapOf(
        "namespaceId" to credential.namespaceId.toApiId(),
        "name" to credential.name.value,
        "gitInstance" to credential.gitInstance.value,
    )

    private fun keyChanges(before: Credential?, after: Credential): Map<String, Any?> {
        val previous = before?.keys.orEmpty().associate { it.id to it.status }
        val changed =
            after.keys
                .filter { previous[it.id] != it.status }
                .map {
                    mapOf(
                        "keyId" to it.id.toApiId(),
                        "fingerprint" to it.fingerprint,
                        "status" to it.status.name,
                        "previousStatus" to previous[it.id]?.name,
                    )
                }
        return mapOf("keys" to changed)
    }
}

/**
 * The audited key changes. The audit operation is `CREDENTIAL_` followed by the enum name, for example
 * `CREDENTIAL_REPLACED`.
 */
enum class KeyOperation {
    REGENERATED,
    ACTIVATED,
    DISCARDED,
    REPLACED,
}
