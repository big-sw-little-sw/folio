package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A private key encrypted at rest, with what decryption needs besides the master secret. Not a data class:
 * nothing should print or compare the ciphertext.
 */
class EncryptedKey(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val salt: ByteArray,
    val masterKeyVersion: Int,
    val algorithm: String,
) {
    override fun toString() = "EncryptedKey(masterKeyVersion=$masterKeyVersion, algorithm=$algorithm)"
}

/**
 * Encrypts private keys with AES-256-GCM under a key derived by HKDF-SHA256 from a master secret, a fresh
 * random salt and the fixed [INFO] label, with a fresh random nonce each time (ADR 0019). The associated data
 * binds each ciphertext to its credential, key and master-key version, so a ciphertext copied to another row
 * or relabelled with another version fails to decrypt.
 *
 * Plaintext arrays this class creates are zeroed before it returns. The JDK's key objects cannot be destroyed,
 * so the derived AES key also lives in a `SecretKeySpec` and the cipher until they are garbage collected.
 */
@Component
class PrivateKeyCipher(
    private val masterKeys: CryptoProperties,
) {
    private val random = SecureRandom()

    fun encrypt(
        plaintext: ByteArray,
        credentialId: CredentialId,
        keyId: KeyId,
    ): EncryptedKey {
        val version = masterKeys.activeKeyVersion
        val salt = randomBytes(SALT_BYTES)
        val nonce = randomBytes(NONCE_BYTES)
        val ciphertext =
            cipher(Cipher.ENCRYPT_MODE, version, salt, nonce, associatedData(credentialId, keyId, version))
                .doFinal(plaintext)
        return EncryptedKey(ciphertext, nonce, salt, version, ALGORITHM)
    }

    /**
     * The plaintext, which the caller zeroes after use. Throws `javax.crypto.AEADBadTagException` if the
     * ciphertext, nonce, salt, master secret or any associated-data field differs from encryption.
     */
    fun decrypt(
        encrypted: EncryptedKey,
        credentialId: CredentialId,
        keyId: KeyId,
    ): ByteArray {
        check(encrypted.algorithm == ALGORITHM) { "Unknown key encryption algorithm '${encrypted.algorithm}'" }
        val version = encrypted.masterKeyVersion
        return cipher(
            Cipher.DECRYPT_MODE,
            version,
            encrypted.salt,
            encrypted.nonce,
            associatedData(credentialId, keyId, version),
        ).doFinal(encrypted.ciphertext)
    }

    private fun cipher(
        mode: Int,
        version: Int,
        salt: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, deriveKey(version, salt), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return cipher
    }

    private fun deriveKey(
        version: Int,
        salt: ByteArray,
    ): SecretKeySpec {
        val secret = masterKeys.secret(version)
        val keyBytes =
            try {
                KDF
                    .getInstance("HKDF-SHA256")
                    .deriveData(
                        HKDFParameterSpec
                            .ofExtract()
                            .addIKM(secret)
                            .addSalt(salt)
                            .thenExpand(INFO, KEY_BYTES),
                    )
            } finally {
                secret.fill(0)
            }
        try {
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    companion object {
        /** Stored per row, so a later format can coexist with this one. */
        const val ALGORITHM = "HKDF-SHA256/AES-256-GCM"

        private const val SALT_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private const val UUID_BYTES = 16

        /** The HKDF context label; changing it makes every stored key undecryptable. */
        private val INFO = "folio credential private key v1".toByteArray(Charsets.US_ASCII)

        /**
         * `credentialId | keyId | masterKeyVersion` as fixed-width big-endian fields: each UUID as 16 bytes
         * (most significant half first) and the version as a 4-byte signed integer, 36 bytes in all. Fixed
         * widths make the encoding unambiguous without separators.
         */
        fun associatedData(
            credentialId: CredentialId,
            keyId: KeyId,
            masterKeyVersion: Int,
        ): ByteArray =
            ByteBuffer
                .allocate(UUID_BYTES + UUID_BYTES + Int.SIZE_BYTES)
                .putUuid(credentialId.value)
                .putUuid(keyId.value)
                .putInt(masterKeyVersion)
                .array()

        private fun ByteBuffer.putUuid(uuid: UUID): ByteBuffer =
            putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
    }
}
