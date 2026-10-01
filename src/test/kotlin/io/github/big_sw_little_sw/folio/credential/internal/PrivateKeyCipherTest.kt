package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateKeyCipherTest {
    private val secret1 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val secret2 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val cipher = PrivateKeyCipher(CryptoProperties(mapOf(1 to secret1, 2 to secret2), activeKeyVersion = 2))
    private val credentialId = CredentialId(UUID.randomUUID())
    private val keyId = KeyId(UUID.randomUUID())
    private val plaintext = "a private key".toByteArray()

    @Test
    fun `decrypts what it encrypted, under the active master-key version`() {
        val encrypted = cipher.encrypt(plaintext, credentialId, keyId)

        assertEquals(2, encrypted.masterKeyVersion)
        assertEquals("HKDF-SHA256/AES-256-GCM", encrypted.algorithm)
        assertEquals(32, encrypted.salt.size)
        assertEquals(12, encrypted.nonce.size)
        assertEquals(plaintext.size + 16, encrypted.ciphertext.size)
        assertContentEquals(plaintext, cipher.decrypt(encrypted, credentialId, keyId))
    }

    @Test
    fun `uses a fresh salt and nonce for every encryption`() {
        val first = cipher.encrypt(plaintext, credentialId, keyId)
        val second = cipher.encrypt(plaintext, credentialId, keyId)

        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `a changed ciphertext, nonce or salt fails decryption`() {
        val encrypted = cipher.encrypt(plaintext, credentialId, keyId)

        listOf(
            encrypted.copy(ciphertext = encrypted.ciphertext.flipFirstBit()),
            encrypted.copy(nonce = encrypted.nonce.flipFirstBit()),
            encrypted.copy(salt = encrypted.salt.flipFirstBit()),
        ).forEach { tampered ->
            assertFailsWith<AEADBadTagException> { cipher.decrypt(tampered, credentialId, keyId) }
        }
    }

    @Test
    fun `another credential ID, key ID or master-key version fails decryption`() {
        val encrypted = cipher.encrypt(plaintext, credentialId, keyId)

        assertFailsWith<AEADBadTagException> { cipher.decrypt(encrypted, CredentialId(UUID.randomUUID()), keyId) }
        assertFailsWith<AEADBadTagException> { cipher.decrypt(encrypted, credentialId, KeyId(UUID.randomUUID())) }
        // Relabelling the version also changes the master secret; the same secret under both versions isolates
        // the associated-data check.
        val sameSecrets = PrivateKeyCipher(CryptoProperties(mapOf(1 to secret1, 2 to secret1), activeKeyVersion = 2))
        val underSameSecret = sameSecrets.encrypt(plaintext, credentialId, keyId)
        assertFailsWith<AEADBadTagException> {
            sameSecrets.decrypt(underSameSecret.copy(masterKeyVersion = 1), credentialId, keyId)
        }
    }

    @Test
    fun `another master secret fails decryption`() {
        val encrypted = cipher.encrypt(plaintext, credentialId, keyId)
        val otherRing = PrivateKeyCipher(CryptoProperties(mapOf(2 to secret1), activeKeyVersion = 2))

        assertFailsWith<AEADBadTagException> { otherRing.decrypt(encrypted, credentialId, keyId) }
    }

    @Test
    fun `refuses an unknown algorithm`() {
        val encrypted = cipher.encrypt(plaintext, credentialId, keyId)

        assertFailsWith<IllegalStateException> {
            cipher.decrypt(
                encrypted.copy(algorithm = "other"),
                credentialId,
                keyId,
            )
        }
    }

    @Test
    fun `associated data is credential ID, key ID and version as 16, 16 and 4 big-endian bytes`() {
        val associatedData =
            PrivateKeyCipher.associatedData(
                CredentialId(UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f")),
                KeyId(UUID.fromString("10111213-1415-1617-1819-1a1b1c1d1e1f")),
                0x01020304,
            )

        assertEquals(
            "000102030405060708090a0b0c0d0e0f" + "101112131415161718191a1b1c1d1e1f" + "01020304",
            HexFormat.of().formatHex(associatedData),
        )
    }

    @Test
    fun `an Ed25519 private key survives encryption as PKCS#8 and still signs for its public key`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encrypted = cipher.encrypt(keyPair.private.encoded, credentialId, keyId)

        val decrypted =
            KeyFactory
                .getInstance("Ed25519")
                .generatePrivate(PKCS8EncodedKeySpec(cipher.decrypt(encrypted, credentialId, keyId)))

        val message = "challenge".toByteArray()
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(decrypted)
                update(message)
                sign()
            }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(OpenSshPublicKey.decode(OpenSshPublicKey.of(keyPair.public).text))
        verifier.update(message)
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun `toString leaves out the ciphertext, nonce and salt`() {
        assertEquals(
            "EncryptedKey(masterKeyVersion=2, algorithm=HKDF-SHA256/AES-256-GCM)",
            cipher.encrypt(plaintext, credentialId, keyId).toString(),
        )
    }

    private fun ByteArray.flipFirstBit() = copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

    private fun EncryptedKey.copy(
        ciphertext: ByteArray = this.ciphertext,
        nonce: ByteArray = this.nonce,
        salt: ByteArray = this.salt,
        masterKeyVersion: Int = this.masterKeyVersion,
        algorithm: String = this.algorithm,
    ) = EncryptedKey(ciphertext, nonce, salt, masterKeyVersion, algorithm)
}
