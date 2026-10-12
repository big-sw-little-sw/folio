package io.github.big_sw_little_sw.folio.credential.internal

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.HexFormat
import kotlin.test.*

class OpenSshPublicKeyTest {
    /**
     * The key is the public key of RFC 8032 section 7.1, test 1. `ssh-keygen -l -f` on the expected line gives the
     * expected fingerprint.
     */
    @Test
    fun `a known key has the line and the fingerprint that ssh-keygen gives`() {
        val publicKey =
            KeyFactory
                .getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(hex("302a300506032b6570032100") + hex(RFC_8032_TEST_1_PUBLIC_KEY)))

        val openSsh = OpenSshPublicKey.of(publicKey)

        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea", openSsh.text)
        assertEquals("SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8", openSsh.fingerprint)
    }

    @Test
    fun `a line decodes to the same JDK key`() {
        val publicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public

        assertEquals(publicKey, OpenSshPublicKey.decode(OpenSshPublicKey.of(publicKey).text))
    }

    @Test
    fun `a key of another algorithm is refused`() {
        val rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().public

        assertFailsWith<IllegalArgumentException> { OpenSshPublicKey.of(rsa) }
    }

    private fun hex(value: String) = HexFormat.of().parseHex(value)

    private companion object {
        const val RFC_8032_TEST_1_PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    }
}
