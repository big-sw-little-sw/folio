package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.testing.*
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.*

class PrivateKeyCipherTest {
    private val secret1 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val secret2 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val cipher = cipher(mapOf(1 to secret1, 2 to secret2), active = 2)
    private val credential = CredentialId(UUID.randomUUID())
    private val key = KeyId(UUID.randomUUID())
    private val plaintext = "a private key".toByteArray()

    @Test
    fun `decrypts what it encrypted, with the active master-key version`() {
        val encrypted = cipher.encrypt(plaintext, credential, key)

        assertEquals(2, encrypted.masterKeyVersion)
        assertEquals("HKDF-SHA256/AES-256-GCM", encrypted.algorithm)
        assertEquals(32, encrypted.salt.size)
        assertEquals(12, encrypted.nonce.size)
        assertEquals(plaintext.size + 16, encrypted.ciphertext.size, "The ciphertext has a tag of 128 bits")
        assertContentEquals(plaintext, cipher.decrypted(encrypted, credential, key))
    }

    @Test
    fun `decrypt sets the plaintext to zero after use`() {
        val encrypted = cipher.encrypt(plaintext, credential, key)
        var used = ByteArray(0)

        cipher.decrypt(encrypted, credential, key) { used = it }

        assertEquals(plaintext.size, used.size)
        assertTrue(used.all { it == 0.toByte() })
    }

    @Test
    fun `reencrypt moves a key to the active version`() {
        val underVersion1 = cipher(mapOf(1 to secret1, 2 to secret2), active = 1).encrypt(plaintext, credential, key)

        val reencrypted = cipher.reencrypt(underVersion1, credential, key)

        assertEquals(2, reencrypted.masterKeyVersion)
        assertContentEquals(plaintext, cipher.decrypted(reencrypted, credential, key))
    }

    /**
     * The vector comes from Python `cryptography` 50.0.2, which uses OpenSSL and not the JDK. The HKDF output was also
     * checked with `openssl kdf -keylen 32 -kdfopt digest:SHA256 -kdfopt hexkey:<secret> -kdfopt hexsalt:<salt>
     * -kdfopt info:"folio credential private key v1" HKDF`. The script:
     *
     * ```
     * key = HKDF(algorithm=SHA256(), length=32, salt=bytes(range(32, 64)),
     *            info=b"folio credential private key v1").derive(bytes(range(0, 32)))
     * aad = bytes(range(0, 16)) + bytes(range(16, 32)) + (7).to_bytes(4, "big")
     * pkcs8 = bytes.fromhex("302e020100300506032b657004220420" + RFC_8032_TEST_1_SECRET_KEY)
     * AESGCM(key).encrypt(bytes(range(64, 76)), pkcs8, aad).hex()
     * ```
     *
     * The plaintext is the PKCS#8 form of the private key of RFC 8032 section 7.1, test 1. A change to the derivation,
     * the label, the associated data or an encoding fails this test.
     */
    @Test
    fun `decrypts a known-answer vector from a tool outside the JDK`() {
        val hex = HexFormat.of()
        val knownAnswer = cipher(mapOf(7 to Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })), 7)
        val encrypted =
            EncryptedKey(
                algorithm = "HKDF-SHA256/AES-256-GCM",
                masterKeyVersion = 7,
                salt = ByteArray(32) { (32 + it).toByte() },
                nonce = ByteArray(12) { (64 + it).toByte() },
                ciphertext =
                hex.parseHex(
                    "22bef4be1e90644140fd846e3a8f39fd626f7b76111710194e0ece27a8c6ac43" +
                        "d50bbc12050cbf69c64f06c5b1036aa97c24b55cabd7c2a973ca555d1c44565d",
                ),
            )

        val pkcs8 =
            knownAnswer.decrypted(
                encrypted,
                CredentialId(UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f")),
                KeyId(UUID.fromString("10111213-1415-1617-1819-1a1b1c1d1e1f")),
            )

        val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        assertEquals("302e020100300506032b657004220420$RFC_8032_TEST_1_SECRET_KEY", hex.formatHex(privateKey.encoded))
    }

    @Test
    fun `each encryption has a new salt and a new nonce`() {
        val first = cipher.encrypt(plaintext, credential, key)
        val second = cipher.encrypt(plaintext, credential, key)

        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `a changed ciphertext, nonce or salt does not decrypt`() {
        val encrypted = cipher.encrypt(plaintext, credential, key)

        listOf(
            encrypted.copy(ciphertext = encrypted.ciphertext.flipFirstBit()),
            encrypted.copy(nonce = encrypted.nonce.flipFirstBit()),
            encrypted.copy(salt = encrypted.salt.flipFirstBit()),
        ).forEach { changed -> assertDoesNotDecrypt(cipher, changed, credential, key) }
    }

    @Test
    fun `another credential, another key or another version does not decrypt`() {
        val encrypted = cipher.encrypt(plaintext, credential, key)

        assertDoesNotDecrypt(cipher, encrypted, CredentialId(UUID.randomUUID()), key)
        assertDoesNotDecrypt(cipher, encrypted, credential, KeyId(UUID.randomUUID()))
        // A different version also selects a different secret. Both versions have the same secret here, so only the
        // associated data differs.
        val sameSecrets = cipher(mapOf(1 to secret1, 2 to secret1), active = 2)
        val underVersion2 = sameSecrets.encrypt(plaintext, credential, key)
        assertDoesNotDecrypt(sameSecrets, underVersion2.copy(masterKeyVersion = 1), credential, key)
    }

    @Test
    fun `another master secret does not decrypt`() {
        val encrypted = cipher.encrypt(plaintext, credential, key)

        assertDoesNotDecrypt(cipher(mapOf(2 to secret1), active = 2), encrypted, credential, key)
    }

    @Test
    fun `an unknown algorithm or a version that the ring does not have does not decrypt`() {
        val encrypted = cipher.encrypt(plaintext, credential, key)

        assertDoesNotDecrypt(cipher, encrypted.copy(algorithm = "other"), credential, key)
        assertDoesNotDecrypt(cipher, encrypted.copy(masterKeyVersion = 3), credential, key)
    }

    @Test
    fun `an Ed25519 private key still signs for its public key after encryption`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encrypted = cipher.encrypt(keyPair.private.encoded, credential, key)

        val decrypted =
            cipher.decrypt(encrypted, credential, key) {
                KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(it))
            }

        assertTrue(signsFor(decrypted, OpenSshPublicKey.decode(OpenSshPublicKey.of(keyPair.public).text)))
    }

    @Test
    fun `toString leaves out the ciphertext, the nonce and the salt`() {
        assertEquals(
            "EncryptedKey(algorithm=HKDF-SHA256/AES-256-GCM, masterKeyVersion=2)",
            cipher.encrypt(plaintext, credential, key).toString(),
        )
    }

    private fun cipher(secrets: Map<Int, String>, active: Int) =
        PrivateKeyCipher(MasterKeyRing(CryptoProperties(secrets.mapKeys { it.key.toString() }, active)))

    /** A copy of the plaintext. The cipher sets its own plaintext to zero after the block. */
    private fun PrivateKeyCipher.decrypted(encrypted: EncryptedKey, credential: CredentialId, key: KeyId): ByteArray =
        decrypt(encrypted, credential, key) { it.copyOf() }

    /** The failure names the key, the credential and the version, and hides the crypto exception type. */
    private fun assertDoesNotDecrypt(
        cipher: PrivateKeyCipher,
        encrypted: EncryptedKey,
        credential: CredentialId,
        key: KeyId,
    ) {
        val failure = assertFailsWith<IllegalStateException> { cipher.decrypt(encrypted, credential, key) {} }
        val expected =
            "Key ${key.toApiId()} of credential ${credential.toApiId()} under master-key version " +
                "${encrypted.masterKeyVersion} does not decrypt."
        assertTrue(failure.message.orEmpty().startsWith(expected), failure.message)
    }

    private fun ByteArray.flipFirstBit() = copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

    private fun EncryptedKey.copy(
        algorithm: String = this.algorithm,
        masterKeyVersion: Int = this.masterKeyVersion,
        salt: ByteArray = this.salt,
        nonce: ByteArray = this.nonce,
        ciphertext: ByteArray = this.ciphertext,
    ) = EncryptedKey(algorithm, masterKeyVersion, salt, nonce, ciphertext)

    private companion object {
        const val RFC_8032_TEST_1_SECRET_KEY = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
