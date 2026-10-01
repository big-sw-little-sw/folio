package io.github.big_sw_little_sw.folio.credential.internal

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CryptoPropertiesTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val encoded = Base64.getEncoder().encodeToString(secret)

    @Test
    fun `decodes the secret of each version`() {
        val properties = CryptoProperties(mapOf(1 to encoded, 2 to encoded), activeKeyVersion = 2)

        assertEquals(setOf(1, 2), properties.versions)
        assertContentEquals(secret, properties.secret(1))
    }

    @Test
    fun `hands out copies, so a caller zeroing its secret leaves the ring intact`() {
        val properties = CryptoProperties(mapOf(1 to encoded), activeKeyVersion = 1)

        properties.secret(1).fill(0)

        assertContentEquals(secret, properties.secret(1))
    }

    @Test
    fun `requires at least one version and an active version that is configured`() {
        assertFailsWith<IllegalArgumentException> { CryptoProperties() }
        val failure =
            assertFailsWith<IllegalArgumentException> { CryptoProperties(mapOf(1 to encoded), activeKeyVersion = 2) }
        assertEquals(
            "folio.crypto.active-key-version 2 is not configured in folio.crypto.master-keys",
            failure.message,
        )
    }

    @Test
    fun `rejects secrets shorter than 32 bytes`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(31))

        val failure = assertFailsWith<IllegalArgumentException> { CryptoProperties(mapOf(1 to short), 1) }

        assertEquals("folio.crypto.master-keys.1 must decode to at least 32 bytes", failure.message)
    }

    @Test
    fun `rejects secrets that are not base64 without quoting them`() {
        val failure = assertFailsWith<IllegalArgumentException> { CryptoProperties(mapOf(1 to "not-base64!"), 1) }

        assertEquals("folio.crypto.master-keys.1 is not base64", failure.message)
    }

    @Test
    fun `rejects versions below 1`() {
        assertFailsWith<IllegalArgumentException> { CryptoProperties(mapOf(0 to encoded), 0) }
    }

    @Test
    fun `toString names versions but not secrets`() {
        val text = CryptoProperties(mapOf(1 to encoded), 1).toString()

        assertEquals("CryptoProperties(versions=[1], activeKeyVersion=1)", text)
        assertFalse(encoded in text)
    }
}
