package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Makes and opens the SSH keys of credentials. v1 has only Ed25519 keys, and the JDK makes them. Folio stores each
 * private key as PKCS#8 bytes that [PrivateKeyCipher] encrypts, and never writes a private key without encryption.
 */
@Component
class SshKeyStore(private val keyRepository: KeyRepository, private val cipher: PrivateKeyCipher) {
    fun generate(credential: CredentialId, status: KeyStatus) {
        val id = keyRepository.newKeyId()
        val keyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        val pkcs8 = keyPair.private.encoded
        val encrypted =
            try {
                cipher.encrypt(pkcs8, credential, id)
            } finally {
                pkcs8.fill(0)
            }
        keyRepository.insert(id, credential, status, OpenSshPublicKey.of(keyPair.public), encrypted)
    }

    fun open(stored: StoredKey): KeyPair {
        val publicKey = OpenSshPublicKey.decode(stored.publicKey)
        val privateKey =
            cipher.decrypt(stored.encrypted, stored.credentialId, stored.id) { pkcs8 ->
                KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            }
        return KeyPair(publicKey, privateKey)
    }

    private companion object {
        const val ALGORITHM = "Ed25519"
    }
}
