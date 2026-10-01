package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.credential.internal.EncryptedKeyRepository
import io.github.big_sw_little_sw.folio.credential.internal.OpenSshPublicKey
import io.github.big_sw_little_sw.folio.credential.internal.PrivateKeyCipher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Decrypted key pairs for Git access (slice 5). Nothing here authorizes: the sync engine runs without a caller.
 * Never expose this over HTTP; private keys must not leave Folio (v1-scope). `ModularityTests` checks that no
 * controller uses it.
 */
@Service
class CredentialKeyPairs(
    private val encryptedKeys: EncryptedKeyRepository,
    private val cipher: PrivateKeyCipher,
) {
    /**
     * The credential's active key pair, decrypted on each call. Callers keep it only while they connect and do
     * not log or persist it. Throws [CredentialDisabledException] for a disabled credential.
     */
    @Transactional(readOnly = true)
    fun active(id: CredentialId): KeyPair {
        val active = encryptedKeys.findActive(id) ?: throw CredentialNotFoundException(id)
        if (active.credentialStatus == CredentialStatus.DISABLED) throw CredentialDisabledException(id)
        val pkcs8 = cipher.decrypt(active.stored.encrypted, id, active.stored.id)
        // The key spec and the JDK key keep their own copies, which cannot be zeroed; this one can.
        try {
            val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            return KeyPair(OpenSshPublicKey.decode(active.publicKey), privateKey)
        } finally {
            pkcs8.fill(0)
        }
    }
}
