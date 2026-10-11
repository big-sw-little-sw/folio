package io.github.big_sw_little_sw.folio.credential.internal

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests check the startup checks of the master-key ring (design 11.3). Spring creates the ring at startup, so a
 * failure here stops the startup. The messages name versions and never secrets.
 */
class MasterKeyRingTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val encoded = Base64.getEncoder().encodeToString(secret)

    @Test
    fun `the ring decodes the secret of each version`() {
        val ring = MasterKeyRing(CryptoProperties(mapOf("1" to encoded, "2" to encoded), activeKeyVersion = 2))

        assertEquals(setOf(1, 2), ring.versions)
        assertEquals(2, ring.activeVersion)
        assertContentEquals(secret, ring.secret(1))
    }

    @Test
    fun `the ring gives copies, so a caller that sets its copy to zero does not change the ring`() {
        val ring = MasterKeyRing(CryptoProperties(mapOf("1" to encoded), activeKeyVersion = 1))

        ring.secret(1).fill(0)

        assertContentEquals(secret, ring.secret(1))
    }

    @Test
    fun `an empty ring or a missing active version stops the startup`() {
        assertFailure("folio.crypto.master-keys is empty. Configure one or more versions.", CryptoProperties())
        assertFailure(
            "folio.crypto.active-key-version is missing.",
            CryptoProperties(mapOf("1" to encoded)),
        )
        assertFailure(
            "folio.crypto.active-key-version 2 is not a version in folio.crypto.master-keys.",
            CryptoProperties(mapOf("1" to encoded), activeKeyVersion = 2),
        )
    }

    @Test
    fun `a secret that is not valid stops the startup without its value`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(31) { 7 })

        assertFailure("folio.crypto.master-keys.1 has fewer than 32 bytes.", CryptoProperties(mapOf("1" to short), 1))
        assertFailure("folio.crypto.master-keys.1 is not base64.", CryptoProperties(mapOf("1" to "not-base64!"), 1))
    }

    @Test
    fun `a version that is not a positive integer stops the startup`() {
        listOf("0", "-1", "01", "v2").forEach { version ->
            assertFailure(
                "folio.crypto.master-keys has the version '$version'. A version is a positive integer.",
                CryptoProperties(mapOf(version to encoded), 1),
            )
        }
    }

    /**
     * Spring Boot reports a failed binding with a property and its value. A failed conversion of a version key once
     * reported the previous entry, which is a secret. Thus `folio.crypto` binds versions as strings. The ring then
     * rejects the version, and its failure message contains no secret.
     */
    @Test
    fun `a binding with a version that is not a number shows no secret`() {
        val other = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "folio.crypto.master-keys.1" to encoded,
                    "folio.crypto.master-keys.abc" to other,
                    "folio.crypto.active-key-version" to "1",
                ),
            )

        val properties = Binder(source).bind("folio.crypto", CryptoProperties::class.java).get()
        val failure = assertFailsWith<IllegalStateException> { MasterKeyRing(properties) }

        assertEquals(
            "folio.crypto.master-keys has the version 'abc'. A version is a positive integer.",
            failure.message,
        )
        val texts = generateSequence<Throwable>(failure) { it.cause }.map { it.toString() }.toList()
        listOf(encoded, other).forEach { secret -> assertTrue(texts.none { secret in it }) }
    }

    @Test
    fun `toString names the versions but not the secrets`() {
        val properties = CryptoProperties(mapOf("1" to encoded), 1)

        assertEquals("CryptoProperties(versions=[1], activeKeyVersion=1)", properties.toString())
        assertEquals("MasterKeyRing(versions=[1], activeVersion=1)", MasterKeyRing(properties).toString())
        assertFalse(encoded in properties.toString())
    }

    private fun assertFailure(message: String, properties: CryptoProperties) {
        val failure = assertFailsWith<IllegalStateException> { MasterKeyRing(properties) }
        assertEquals(message, failure.message)
        properties.masterKeys.values.forEach { assertFalse(it in message) }
    }
}
