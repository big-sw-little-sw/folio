package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Makes and opens the SSH keys of credentials. v1 has only Ed25519 keys, and the JDK makes them (design 10.4). Folio
 * stores each private key as PKCS#8 bytes that [PrivateKeyCipher] encrypts. Folio never writes a private key without
 * encryption (design 11.1).
 */
@Component
class SshKeys(private val keys: KeyRepository, private val cipher: PrivateKeyCipher) {
    /** Generates a key pair, encrypts its private key and stores the key with [status]. */
    fun generate(credential: CredentialId, status: KeyStatus) {
        val id = keys.newKeyId()
        val keyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        val pkcs8 = keyPair.private.encoded
        val encrypted =
            try {
                cipher.encrypt(pkcs8, credential, id)
            } finally {
                pkcs8.fill(0)
            }
        keys.insert(id, credential, status, OpenSshPublicKey.of(keyPair.public), encrypted)
    }

    /**
     * Decrypts the private key of [stored]. [PrivateKeyCipher] sets the PKCS#8 bytes to zero as soon as the JDK key
     * object exists. The JDK key objects keep their own copies, which Folio cannot set to zero (design 11.2). Throws
     * `IllegalStateException` that names the key if the key does not decrypt.
     */
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
