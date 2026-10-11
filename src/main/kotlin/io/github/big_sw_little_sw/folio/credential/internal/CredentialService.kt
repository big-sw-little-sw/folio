package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
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
 * The credential operations of the HTTP routes and the UI pages (design 11.1). Each operation needs `MANAGE` on the
 * Namespace that owns the credential (design 7.3).
 *
 * Each operation finds the credential first (404), then authorizes (403 or 401), then examines the state (409). Each
 * change locks the credential row first, so the changes of one credential run one at a time. A change writes an audit
 * record only when it changes something.
 */
@Service
class CredentialService(
    private val credentials: CredentialRepository,
    private val keys: KeyRepository,
    private val sshKeys: SshKeys,
    private val namespaces: Namespaces,
    private val access: Access,
    private val audit: CredentialAudit,
) {
    /** Creates the credential with its first key, and activates that key. */
    @Transactional
    fun create(caller: Caller, namespace: NamespaceId, name: Slug, gitInstance: Slug): Credential {
        namespace(namespace)
        access.requireAllowed(caller, Action.MANAGE, namespace)
        val id = credentials.insert(namespace, name, gitInstance)
        sshKeys.generate(id, KeyStatus.ACTIVE)
        val created = current(id)
        audit.created(caller, created)
        return created
    }

    @SnapshotRead
    fun get(caller: Caller, id: CredentialId): Credential {
        val row = credentials.find(id) ?: throw CredentialException.CredentialNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, row.namespaceId)
        return withKeys(row)
    }

    @SnapshotRead
    fun list(caller: Caller, namespace: NamespaceId): NamespaceCredentials {
        val owner = namespace(namespace)
        access.requireAllowed(caller, Action.MANAGE, namespace)
        val rows = credentials.inNamespace(namespace)
        val keysById = keys.inCredentials(rows.map { it.id })
        return NamespaceCredentials(owner, rows.map { it.toCredential(owner.path, keysById[it.id].orEmpty()) })
    }

    /** Adds a pending key. The active key stays in use until an activation. */
    @Transactional
    fun regenerate(caller: Caller, id: CredentialId): Credential =
        changeKeys(caller, id, KeyOperation.REGENERATED) { before ->
            if (before.pendingKey != null) throw CredentialException.PendingKeyExists(id)
            sshKeys.generate(id, KeyStatus.PENDING)
        }

    /**
     * Activates the pending key [key] and retires the previous active key. The caller names the key, so a caller never
     * activates a key that another person generated in the meantime (design 11.1).
     */
    @Transactional
    fun activate(caller: Caller, id: CredentialId, key: KeyId): Credential =
        changeKeys(caller, id, KeyOperation.ACTIVATED) { before ->
            if (before.pendingKey?.id != key) throw CredentialException.KeyNotPending(id, key)
            keys.activate(id, key)
        }

    /** Retires the pending key [key]. The active key does not change. */
    @Transactional
    fun discard(caller: Caller, id: CredentialId, key: KeyId): Credential =
        changeKeys(caller, id, KeyOperation.DISCARDED) { before ->
            if (before.pendingKey?.id != key) throw CredentialException.KeyNotPending(id, key)
            keys.retire(id, KeyStatus.PENDING)
        }

    /** For emergencies: retires the active key and the pending key, then generates a new key and activates it. */
    @Transactional
    fun replace(caller: Caller, id: CredentialId): Credential = changeKeys(caller, id, KeyOperation.REPLACED) {
        keys.retireAll(id)
        sshKeys.generate(id, KeyStatus.ACTIVE)
    }

    /**
     * Disables the credential permanently. Folio keeps its encrypted keys, so a later version can add "enable". A
     * second disable changes nothing and writes no audit record.
     */
    @Transactional
    fun disable(caller: Caller, id: CredentialId): Credential {
        val row = lock(caller, id)
        if (row.status == CredentialStatus.DISABLED) return withKeys(row)
        credentials.disable(id)
        val disabled = current(id)
        audit.disabled(caller, disabled)
        return disabled
    }

    /** Deletes the credential and all its keys, also when it is disabled. Returns the credential before the delete. */
    @Transactional
    fun delete(caller: Caller, id: CredentialId): Credential {
        val credential = withKeys(lock(caller, id))
        credentials.delete(id)
        audit.deleted(caller, credential)
        return credential
    }

    /** Locks the credential, requires an enabled credential, runs [change] and writes the audit record. */
    private fun changeKeys(
        caller: Caller,
        id: CredentialId,
        operation: KeyOperation,
        change: (Credential) -> Unit,
    ): Credential {
        val before = withKeys(lock(caller, id))
        if (!before.enabled) throw CredentialException.CredentialDisabled(id)
        change(before)
        val after = current(id)
        audit.keysChanged(caller, operation, before, after)
        return after
    }

    /** Locks the credential row (404 if it does not exist), then requires `MANAGE` on its Namespace. */
    private fun lock(caller: Caller, id: CredentialId): CredentialRow {
        val row = credentials.lock(id) ?: throw CredentialException.CredentialNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, row.namespaceId)
        return row
    }

    /** The credential after a change in this transaction. The transaction holds the row lock, so the row exists. */
    private fun current(id: CredentialId): Credential =
        withKeys(checkNotNull(credentials.find(id)) { "Credential ${id.toApiId()} is locked but missing." })

    private fun withKeys(row: CredentialRow): Credential =
        row.toCredential(namespace(row.namespaceId).path, keys.inCredentials(listOf(row.id))[row.id].orEmpty())

    /**
     * The foreign key from `credential` stops the delete of a Namespace that owns a credential. Thus only [create] and
     * [list] can get a 404 here, because their Namespace ID comes from the request.
     */
    private fun namespace(id: NamespaceId): Namespace =
        namespaces.find(id) ?: throw CatalogException.NamespaceNotFound(id)
}
