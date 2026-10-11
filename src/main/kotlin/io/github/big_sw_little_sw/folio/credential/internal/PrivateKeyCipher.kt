package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec

/**
 * An encrypted private key and the values that its decryption needs, except the master secret. The five values are
 * the encryption columns of a key row. The class is not a data class and overrides `toString`, so a log line or an
 * error message never shows the ciphertext.
 */
class EncryptedKey(
    val algorithm: String,
    val masterKeyVersion: Int,
    val salt: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun toString() = "EncryptedKey(algorithm=$algorithm, masterKeyVersion=$masterKeyVersion)"
}

/**
 * Encrypts private keys at rest (design 11.2). AES-256-GCM encrypts each key with a new random nonce of 96 bits and a
 * tag of 128 bits. HKDF-SHA256 derives the AES key from the master secret, a new random salt of 32 bytes and the fixed
 * label [INFO]. The associated data connects each ciphertext to its credential, its key and its master-key version.
 * Thus a ciphertext that moves to another row or gets another version does not decrypt.
 *
 * The class uses only the JDK providers (design 10.4). It sets to zero each copy of a master secret after use. The JDK
 * key objects keep their own copies of the derived key, and Folio cannot set those to zero.
 */
@Component
class PrivateKeyCipher(private val ring: MasterKeyRing) {
    private val random = SecureRandom()

    /** Encrypts [plaintext] with the active master-key version. The caller sets [plaintext] to zero after use. */
    fun encrypt(plaintext: ByteArray, credential: CredentialId, key: KeyId): EncryptedKey {
        val version = ring.activeVersion
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, version, salt, nonce, associatedData(credential, key, version))
        return EncryptedKey(ALGORITHM, version, salt, nonce, cipher.doFinal(plaintext))
    }

    /**
     * Decrypts the private key, runs [use] with the plaintext and then sets the plaintext to zero. Thus no caller keeps
     * a plaintext after use.
     *
     * Throws `IllegalStateException` if the key does not decrypt: a value differs from the encryption (for example the
     * ciphertext, the salt, the master secret or a part of the associated data), the algorithm is unknown or the ring
     * does not have the version. The message names the key, the credential and the version, and never a secret. Thus
     * callers never see crypto exception types, and a log names the row to repair.
     */
    fun <T> decrypt(encrypted: EncryptedKey, credential: CredentialId, key: KeyId, use: (ByteArray) -> T): T {
        val plaintext = plaintext(encrypted, credential, key)
        try {
            return use(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Encrypts the key again with the active master-key version. The plaintext exists only inside this method. */
    fun reencrypt(encrypted: EncryptedKey, credential: CredentialId, key: KeyId): EncryptedKey =
        decrypt(encrypted, credential, key) { encrypt(it, credential, key) }

    private fun plaintext(encrypted: EncryptedKey, credential: CredentialId, key: KeyId): ByteArray {
        val version = encrypted.masterKeyVersion
        val failure =
            "Key ${key.toApiId()} of credential ${credential.toApiId()} under master-key version $version does not " +
                "decrypt."
        try {
            check(encrypted.algorithm == ALGORITHM) { "The algorithm '${encrypted.algorithm}' is unknown." }
            val associatedData = associatedData(credential, key, version)
            return cipher(Cipher.DECRYPT_MODE, version, encrypted.salt, encrypted.nonce, associatedData)
                .doFinal(encrypted.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException(failure, e)
        } catch (e: IllegalStateException) {
            throw IllegalStateException(failure, e)
        }
    }

    private fun cipher(mode: Int, version: Int, salt: ByteArray, nonce: ByteArray, associatedData: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, derivedKey(version, salt), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(associatedData)
        }

    private fun derivedKey(version: Int, salt: ByteArray): SecretKey {
        val secret = ring.secret(version)
        try {
            val parameters =
                HKDFParameterSpec
                    .ofExtract()
                    .addIKM(secret)
                    .addSalt(salt)
                    .thenExpand(INFO, KEY_BYTES)
            return KDF.getInstance(KDF_ALGORITHM).deriveKey("AES", parameters)
        } finally {
            secret.fill(0)
        }
    }

    companion object {
        /**
         * Each key row stores this name, so a later format can exist next to this one. A change to [INFO], to the
         * associated data or to an encoding makes all stored keys unreadable. The known-answer test fails on such a
         * change, so the build stops.
         */
        const val ALGORITHM = "HKDF-SHA256/AES-256-GCM"

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KDF_ALGORITHM = "HKDF-SHA256"
        private const val SALT_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private const val UUID_BYTES = 16
        private val INFO = "folio credential private key v1".toByteArray(Charsets.US_ASCII)

        /**
         * The credential UUID, the key UUID and the master-key version, 36 bytes in fixed-width big-endian format
         * (design 11.2). Each UUID has 16 bytes, most significant half first. The version has 4 bytes. Fixed widths
         * make the encoding unambiguous without separators.
         */
        private fun associatedData(credential: CredentialId, key: KeyId, masterKeyVersion: Int): ByteArray = ByteBuffer
            .allocate(UUID_BYTES + UUID_BYTES + Int.SIZE_BYTES)
            .putUuid(credential.value)
            .putUuid(key.value)
            .putInt(masterKeyVersion)
            .array()

        private fun ByteBuffer.putUuid(uuid: UUID): ByteBuffer =
            putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
    }
}
