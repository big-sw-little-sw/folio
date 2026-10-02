package io.github.big_sw_little_sw.folio.audit.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetCreated
import io.github.big_sw_little_sw.folio.configset.ConfigSetDeleted
import io.github.big_sw_little_sw.folio.configset.ConfigSetEvent
import io.github.big_sw_little_sw.folio.configset.ConfigSetMoved
import io.github.big_sw_little_sw.folio.configset.ConfigSetRenamed
import io.github.big_sw_little_sw.folio.configset.ConfigSetRuleDeleted
import io.github.big_sw_little_sw.folio.configset.ConfigSetRulePut
import io.github.big_sw_little_sw.folio.credential.CredentialChange
import io.github.big_sw_little_sw.folio.credential.CredentialChanged
import io.github.big_sw_little_sw.folio.credential.MasterKeysReencrypted
import io.github.big_sw_little_sw.folio.namespace.NamespaceCreated
import io.github.big_sw_little_sw.folio.namespace.NamespaceDeleted
import io.github.big_sw_little_sw.folio.namespace.NamespaceEvent
import io.github.big_sw_little_sw.folio.namespace.NamespaceMoved
import io.github.big_sw_little_sw.folio.namespace.NamespaceRenamed
import io.github.big_sw_little_sw.folio.namespace.NamespaceRuleDeleted
import io.github.big_sw_little_sw.folio.namespace.NamespaceRulePut
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.sync.SyncEvent
import io.github.big_sw_little_sw.folio.sync.SyncFailed
import io.github.big_sw_little_sw.folio.sync.SyncRecovered
import io.github.big_sw_little_sw.folio.sync.SyncRequested
import io.github.big_sw_little_sw.folio.sync.SyncRevisionChanged

// Mapping of the audited modules' events to records (ADR 0038). Details carry IDs, paths, fingerprints, codes and
// revisions only: never key material, ciphertext, secrets, tokens, transport output or file contents.

fun NamespaceEvent.toAuditEntry(): AuditEntry {
    val (action, details) =
        when (this) {
            is NamespaceCreated -> AuditAction.NAMESPACE_CREATED to emptyMap()
            is NamespaceRenamed -> AuditAction.NAMESPACE_RENAMED to mapOf("previousPath" to text(previousPath))
            is NamespaceMoved -> AuditAction.NAMESPACE_MOVED to mapOf("previousPath" to text(previousPath))
            is NamespaceDeleted -> AuditAction.NAMESPACE_DELETED to emptyMap()
            is NamespaceRulePut -> AuditAction.POLICY_RULE_PUT to details(rule)
            is NamespaceRuleDeleted -> AuditAction.POLICY_RULE_DELETED to details(action)
        }
    return AuditEntry(action, ResourceType.NAMESPACE, id.value, text(path), details)
}

fun ConfigSetEvent.toAuditEntry(): AuditEntry {
    val (action, details) =
        when (this) {
            is ConfigSetCreated -> {
                AuditAction.CONFIG_SET_CREATED to
                    mapOf(
                        "credentialId" to source.credentialId.value,
                        "repositoryPath" to source.repositoryPath.value,
                        "branch" to source.branch.value,
                        "rootPath" to source.rootPath.value,
                    )
            }

            is ConfigSetRenamed -> {
                AuditAction.CONFIG_SET_RENAMED to mapOf("previousPath" to previousPath.toString())
            }

            is ConfigSetMoved -> {
                AuditAction.CONFIG_SET_MOVED to mapOf("previousPath" to previousPath.toString())
            }

            is ConfigSetDeleted -> {
                AuditAction.CONFIG_SET_DELETED to emptyMap()
            }

            is ConfigSetRulePut -> {
                AuditAction.POLICY_RULE_PUT to details(rule)
            }

            is ConfigSetRuleDeleted -> {
                AuditAction.POLICY_RULE_DELETED to details(action)
            }
        }
    return AuditEntry(action, ResourceType.CONFIG_SET, id.value, path.toString(), details)
}

/** Keys by ID and fingerprint only; public keys are not repeated. */
fun CredentialChanged.toAuditEntry(): AuditEntry {
    val action =
        when (change) {
            CredentialChange.CREATED -> AuditAction.CREDENTIAL_CREATED
            CredentialChange.KEY_REGENERATED -> AuditAction.CREDENTIAL_KEY_REGENERATED
            CredentialChange.KEY_ACTIVATED -> AuditAction.CREDENTIAL_KEY_ACTIVATED
            CredentialChange.KEY_DISCARDED -> AuditAction.CREDENTIAL_KEY_DISCARDED
            CredentialChange.KEY_REPLACED -> AuditAction.CREDENTIAL_KEY_REPLACED
            CredentialChange.DISABLED -> AuditAction.CREDENTIAL_DISABLED
        }
    val keys =
        changedKeys.map { mapOf("keyId" to it.id.value, "fingerprint" to it.fingerprint, "status" to it.status.name) }
    val details = mapOf("gitInstance" to credential.gitInstance, "name" to credential.name.value, "keys" to keys)
    return AuditEntry(action, ResourceType.CREDENTIAL, credential.id.value, null, details)
}

/** Counts per master-key version, not per key. */
fun MasterKeysReencrypted.toAuditEntry() =
    AuditEntry(
        AuditAction.MASTER_KEYS_REENCRYPTED,
        ResourceType.MASTER_KEY_RING,
        null,
        null,
        mapOf(
            "complete" to complete,
            "activeVersion" to usage.activeVersion,
            "reencryptedByVersion" to reencrypted.toSortedMap(),
            "keysByVersion" to usage.keysByVersion,
        ),
    )

fun SyncEvent.toAuditEntry(): AuditEntry {
    val (action, details) =
        when (this) {
            is SyncRequested -> {
                AuditAction.SYNC_REQUESTED to emptyMap()
            }

            is SyncRevisionChanged -> {
                AuditAction.SYNC_REVISION_CHANGED to
                    mapOf("revision" to revision, "previousRevision" to previousRevision)
            }

            is SyncFailed -> {
                AuditAction.SYNC_FAILED to
                    mapOf("failureCode" to failureCode, "previousFailureCode" to previousFailureCode)
            }

            is SyncRecovered -> {
                AuditAction.SYNC_RECOVERED to
                    mapOf(
                        "revision" to revision,
                        "previousRevision" to previousRevision,
                        "previousFailureCode" to previousFailureCode,
                    )
            }
        }
    return AuditEntry(action, ResourceType.CONFIG_SET, id.value, path.toString(), details)
}

private fun text(path: List<Slug>) = path.joinToString("/")

private fun details(rule: Rule) =
    mapOf("ruleAction" to rule.action.name, "subjects" to rule.subjects.map { it.toString() }.sorted())

private fun details(action: Action) = mapOf("ruleAction" to action.name)
