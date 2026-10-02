package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.credential.internal.CredentialRepository
import io.github.big_sw_little_sw.folio.credential.internal.EncryptedKeyRepository
import io.github.big_sw_little_sw.folio.credential.internal.OpenSshPublicKey
import io.github.big_sw_little_sw.folio.credential.internal.PrivateKeyCipher
import io.github.big_sw_little_sw.folio.credential.internal.UsableKey
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec

/**
 * A decrypted key pair and the Git service instance it belongs to. Not a data class, so that printing it cannot
 * show more than the JDK's own `KeyPair.toString`.
 */
class CredentialKeyPair(
    val gitInstance: GitInstance,
    val keyPair: KeyPair,
)

/**
 * Decrypted key pairs for Git access. Nothing here authorizes: the sync engine runs without a caller.
 * Never expose this over HTTP; private keys must not leave Folio (v1-scope). `ModularityTests` checks that no
 * HTTP layer uses it.
 *
 * Callers keep a key pair only while they connect and do not log or persist it. Both methods throw
 * [CredentialNotFoundException] for a missing credential and [CredentialDisabledException] for a disabled one.
 */
@Service
class CredentialKeyPairs(
    private val credentials: CredentialRepository,
    private val encryptedKeys: EncryptedKeyRepository,
    private val cipher: PrivateKeyCipher,
    private val instances: GitInstances,
) {
    /** The credential's active key pair, decrypted on each call. */
    @Transactional(readOnly = true)
    fun active(id: CredentialId): CredentialKeyPair =
        decrypt(encryptedKeys.findActive(id) ?: throw CredentialNotFoundException(id))

    /**
     * The credential's pending key pair [keyId], to verify access before activation (ADR 0027). Throws
     * [KeyNotPendingException] if [keyId] is not the credential's pending key.
     */
    @Transactional(readOnly = true)
    fun pending(
        id: CredentialId,
        keyId: KeyId,
    ): CredentialKeyPair = decrypt(encryptedKeys.findPending(id, keyId) ?: throw notPending(id, keyId))

    /** Why [keyId] is not a usable pending key of [id]. */
    private fun notPending(
        id: CredentialId,
        keyId: KeyId,
    ): CredentialException {
        val credential = credentials.findById(id) ?: return CredentialNotFoundException(id)
        if (credential.status == CredentialStatus.DISABLED) return CredentialDisabledException(id)
        return KeyNotPendingException(id, keyId)
    }

    private fun decrypt(key: UsableKey): CredentialKeyPair {
        val id = key.stored.credentialId
        if (key.credentialStatus == CredentialStatus.DISABLED) throw CredentialDisabledException(id)
        // A credential whose instance was removed from configuration cannot reach any Git service (ADR 0016).
        val instance = instances.find(key.gitInstance) ?: throw GitInstanceNotConfiguredException(id, key.gitInstance)
        val pkcs8 = cipher.decrypt(key.stored.encrypted, id, key.stored.id)
        // The key spec and the JDK key keep their own copies, which cannot be zeroed; this one can.
        try {
            val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            return CredentialKeyPair(instance, KeyPair(OpenSshPublicKey.decode(key.publicKey), privateKey))
        } finally {
            pkcs8.fill(0)
        }
    }
}
