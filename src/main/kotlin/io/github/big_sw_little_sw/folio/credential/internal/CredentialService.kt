package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.audit.AuditEntry
import io.github.big_sw_little_sw.folio.audit.AuditLog
import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.Namespace
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Namespaces
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The credentials of a Namespace, in name order. */
data class NamespaceCredentials(val namespace: Namespace, val credentials: List<Credential>)

/**
 * The credential operations of the HTTP routes and the UI pages. Each operation needs `MANAGE` on the Namespace that
 * owns the credential. Each operation finds the credential or the Namespace first (404), then authorizes (403 or 401),
 * then examines the state (409). Each change locks the credential row, so the changes of one credential run one at a
 * time. A change writes an audit record only when it changes something.
 */
@Service
class CredentialService(
    private val credentialRepository: CredentialRepository,
    private val keyRepository: KeyRepository,
    private val sshKeyStore: SshKeyStore,
    private val namespaces: Namespaces,
    private val access: Access,
    private val auditLog: AuditLog,
) {
    /** Creates the credential with its first key, and activates that key. */
    @Transactional
    fun create(caller: Caller, namespace: NamespaceId, name: Slug, gitInstance: Slug): Credential {
        namespaces.require(caller, Action.MANAGE, namespace)
        val id = credentialRepository.insert(namespace, name, gitInstance)
        sshKeyStore.generate(id, KeyStatus.ACTIVE)
        val created = current(id)
        auditLog.record(caller, "CREDENTIAL_CREATED", created, identity(created) + keyChanges(null, created))
        return created
    }

    @SnapshotRead
    fun get(caller: Caller, id: CredentialId): Credential {
        val row = credentialRepository.find(id) ?: throw CredentialException.CredentialNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, row.namespaceId)
        return withKeys(row)
    }

    @SnapshotRead
    fun list(caller: Caller, namespace: NamespaceId): NamespaceCredentials {
        val owner = namespaces.require(caller, Action.MANAGE, namespace)
        val rows = credentialRepository.inNamespace(namespace)
        val keysById = keyRepository.inCredentials(rows.map { it.id })
        return NamespaceCredentials(owner, rows.map { it.toCredential(owner.path, keysById[it.id].orEmpty()) })
    }

    /** Adds a pending key. The active key stays in use until an activation. */
    @Transactional
    fun regenerate(caller: Caller, id: CredentialId): Credential =
        changeKeys(caller, id, "CREDENTIAL_REGENERATED") { before ->
            if (before.pendingKey != null) throw CredentialException.PendingKeyExists(id)
            sshKeyStore.generate(id, KeyStatus.PENDING)
        }

    /** Activates the pending key [key] and retires the previous active key. */
    @Transactional
    fun activate(caller: Caller, id: CredentialId, key: KeyId): Credential =
        changeKeys(caller, id, "CREDENTIAL_ACTIVATED") { before ->
            if (before.pendingKey?.id != key) throw CredentialException.KeyNotPending(id, key)
            keyRepository.activate(id, key)
        }

    /** Retires the pending key [key]. The active key does not change. */
    @Transactional
    fun discard(caller: Caller, id: CredentialId, key: KeyId): Credential =
        changeKeys(caller, id, "CREDENTIAL_DISCARDED") { before ->
            if (before.pendingKey?.id != key) throw CredentialException.KeyNotPending(id, key)
            keyRepository.retire(id, KeyStatus.PENDING)
        }

    /** For emergencies: retires the active key and the pending key, then generates a new key and activates it. */
    @Transactional
    fun replace(caller: Caller, id: CredentialId): Credential = changeKeys(caller, id, "CREDENTIAL_REPLACED") {
        keyRepository.retireAll(id)
        sshKeyStore.generate(id, KeyStatus.ACTIVE)
    }

    /**
     * Disables the credential permanently: no operation enables it again. Folio keeps its encrypted keys, so a later
     * version can add an enable operation. A second disable changes nothing and writes no audit record.
     */
    @Transactional
    fun disable(caller: Caller, id: CredentialId): Credential {
        val row = lock(caller, id)
        if (!row.enabled) return withKeys(row)
        credentialRepository.disable(id)
        val disabled = current(id)
        auditLog.record(caller, "CREDENTIAL_DISABLED", disabled)
        return disabled
    }

    /** Deletes the credential and all its keys, also when it is disabled. Returns the credential before the delete. */
    @Transactional
    fun delete(caller: Caller, id: CredentialId): Credential {
        val credential = withKeys(lock(caller, id))
        credentialRepository.delete(id)
        auditLog.record(caller, "CREDENTIAL_DELETED", credential, identity(credential))
        return credential
    }

    private fun changeKeys(
        caller: Caller,
        id: CredentialId,
        operation: String,
        change: (Credential) -> Unit,
    ): Credential {
        val before = withKeys(lock(caller, id))
        if (!before.enabled) throw CredentialException.CredentialDisabled(id)
        change(before)
        val after = current(id)
        auditLog.record(caller, operation, after, keyChanges(before, after))
        return after
    }

    /**
     * Finds the credential (404), requires `MANAGE` on its Namespace, then locks the credential row. [Access] takes the
     * shared tree lock, so the tree lock comes before the row lock. In the other order, a change could hold the row and
     * wait for the tree lock, while onboarding holds the tree lock and waits for the row at the foreign key of
     * `git_source`. The two then deadlock. A credential never changes its Namespace, so the authorization stays valid
     * after the lock.
     */
    private fun lock(caller: Caller, id: CredentialId): CredentialRow {
        val found = credentialRepository.find(id) ?: throw CredentialException.CredentialNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, found.namespaceId)
        return credentialRepository.lock(id) ?: throw CredentialException.CredentialNotFound(id)
    }

    /** The credential after a change in this transaction. The transaction holds the row lock, so the row exists. */
    private fun current(id: CredentialId): Credential =
        withKeys(checkNotNull(credentialRepository.find(id)) { "Credential ${id.toApiId()} is locked but missing." })

    private fun withKeys(row: CredentialRow): Credential = row.toCredential(
        namespace(row.namespaceId).path,
        keyRepository.inCredentials(listOf(row.id))[row.id].orEmpty(),
    )

    /**
     * The foreign key from `credential` stops the delete of a Namespace that owns a credential. Thus the Namespace of a
     * credential row always exists.
     */
    private fun namespace(id: NamespaceId): Namespace =
        checkNotNull(namespaces.find(id)) { "Namespace ${id.toApiId()} owns a credential but is missing." }
}

private fun AuditLog.record(
    caller: Caller,
    operation: String,
    credential: Credential,
    details: Map<String, Any?> = emptyMap(),
) = record(
    AuditEntry(
        caller = caller,
        operation = operation,
        resourceType = "CREDENTIAL",
        resourceId = credential.id.value,
        path = credential.namespacePath.toString(),
        details = details,
    ),
)

/**
 * The details of a create and a delete give the Namespace ID, the name and the Git instance. Without them, a record of
 * a deleted credential has only its UUID, and a person cannot tell which credential it was.
 */
private fun identity(credential: Credential) = mapOf(
    "namespaceId" to credential.namespaceId.toApiId(),
    "name" to credential.name.value,
    "gitInstance" to credential.gitInstance.value,
)

/** Names each key whose status changed by its API ID and fingerprint. An audit record never contains key material. */
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
