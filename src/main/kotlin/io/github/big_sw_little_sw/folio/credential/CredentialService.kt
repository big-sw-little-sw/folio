package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.credential.internal.CredentialRepository
import io.github.big_sw_little_sw.folio.credential.internal.OpenSshPublicKey
import io.github.big_sw_little_sw.folio.credential.internal.PrivateKeyCipher
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.KeyPairGenerator

/**
 * Credentials and their key lifecycle (ADR 0017). Credentials sit outside the namespace tree, so every operation
 * is authorized at the root, where only super admins hold actions in v1 (ADR 0018). Lookups come before
 * authorization, so a missing credential gives not found rather than denied (ADR 0010).
 *
 * Every key change locks the credential row first. Changes to one credential then run one at a time: two
 * regenerations cannot both see no pending key, and an activation cannot interleave with a replacement. The
 * partial unique indexes on `credential_key` back this up; with the lock they are never violated.
 */
@Service
class CredentialService(
    private val credentials: CredentialRepository,
    private val cipher: PrivateKeyCipher,
    private val instances: GitInstances,
    private val policy: PolicyService,
) {
    /** Creates a credential for [gitInstance] with a first key that is active at once. */
    @Transactional
    fun create(
        gitInstance: String,
        name: CredentialName,
    ): Credential {
        policy.requireAllowed(Action.CREDENTIAL_MANAGE, ROOT)
        if (instances.find(gitInstance) == null) throw UnknownGitInstanceException(gitInstance)
        val id = credentials.insert(gitInstance, name)
        generate(id, KeyStatus.ACTIVE)
        return existing(id)
    }

    @Transactional(readOnly = true)
    fun get(id: CredentialId): Credential {
        val credential = existing(id)
        policy.requireAllowed(Action.CREDENTIAL_VIEW, ROOT)
        return credential
    }

    /**
     * Succeeds if the caller may use the credential for a ConfigSet's source and it is enabled. Requires
     * [Action.CREDENTIAL_USE] at the root, so super admins only in v1 (ADR 0023).
     */
    @Transactional(readOnly = true)
    fun requireUsable(id: CredentialId) {
        val credential = existing(id)
        policy.requireAllowed(Action.CREDENTIAL_USE, ROOT)
        if (credential.status == CredentialStatus.DISABLED) throw CredentialDisabledException(id)
    }

    /** All credentials, oldest first. */
    @Transactional(readOnly = true)
    fun list(): List<Credential> {
        policy.requireAllowed(Action.CREDENTIAL_VIEW, ROOT)
        return credentials.findAll()
    }

    /** Adds a `PENDING` key for the administrator to register with the Git service; the active key stays in use. */
    @Transactional
    fun regenerate(id: CredentialId): Credential {
        lockEnabled(id)
        if (pendingKey(id) != null) throw PendingKeyExistsException(id)
        generate(id, KeyStatus.PENDING)
        return existing(id)
    }

    /**
     * Makes the pending key [keyId] active and retires the previous active key. Naming the key ensures the
     * administrator activates the key they registered, not one generated since (ADR 0017).
     */
    @Transactional
    fun activate(
        id: CredentialId,
        keyId: KeyId,
    ): Credential {
        lockEnabled(id)
        if (pendingKey(id) != keyId) throw KeyNotPendingException(id, keyId)
        // Retire first: the partial unique index allows one ACTIVE key at any moment, even within a transaction.
        credentials.retire(id, KeyStatus.ACTIVE)
        credentials.activate(keyId)
        return existing(id)
    }

    /** Emergency replacement: retires the active key and any pending key, and activates a new key at once. */
    @Transactional
    fun replace(id: CredentialId): Credential {
        lockEnabled(id)
        credentials.retire(id, KeyStatus.ACTIVE)
        credentials.retire(id, KeyStatus.PENDING)
        generate(id, KeyStatus.ACTIVE)
        return existing(id)
    }

    /** Disabling a disabled credential succeeds and changes nothing. */
    @Transactional
    fun disable(id: CredentialId): Credential {
        credentials.lock(id) ?: throw CredentialNotFoundException(id)
        policy.requireAllowed(Action.CREDENTIAL_MANAGE, ROOT)
        credentials.updateStatus(id, CredentialStatus.DISABLED)
        return existing(id)
    }

    private fun lockEnabled(id: CredentialId) {
        val status = credentials.lock(id) ?: throw CredentialNotFoundException(id)
        policy.requireAllowed(Action.CREDENTIAL_MANAGE, ROOT)
        if (status == CredentialStatus.DISABLED) throw CredentialDisabledException(id)
    }

    /** Generates an Ed25519 key pair and stores it with the private key encrypted; the plaintext is zeroed. */
    private fun generate(
        credentialId: CredentialId,
        status: KeyStatus,
    ) {
        val keyId = credentials.newKeyId()
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pkcs8 = keyPair.private.encoded
        val encrypted =
            try {
                cipher.encrypt(pkcs8, credentialId, keyId)
            } finally {
                pkcs8.fill(0)
            }
        credentials.insertKey(keyId, credentialId, status, OpenSshPublicKey.of(keyPair.public), encrypted)
    }

    private fun pendingKey(id: CredentialId): KeyId? =
        existing(id).keys.singleOrNull { it.status == KeyStatus.PENDING }?.id

    private fun existing(id: CredentialId): Credential =
        credentials.findById(id) ?: throw CredentialNotFoundException(id)

    private companion object {
        /** The root above all namespaces; no rules attach there, so only super admins pass (ADR 0012). */
        val ROOT = emptyList<ResourceRef>()
    }
}
