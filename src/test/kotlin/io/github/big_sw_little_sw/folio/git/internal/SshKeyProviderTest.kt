package io.github.big_sw_little_sw.folio.git.internal

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.util.security.SecurityUtils
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KDF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** These tests make sure that Bouncy Castle serves only the Ed25519 keys of sshd, not Folio's crypto (design 10.4). */
class SshKeyProviderTest {
    @Test
    fun `sshd supports Ed25519`() {
        assertTrue(SecurityUtils.isEDDSACurveSupported())
    }

    @Test
    fun `Folio's own key generation, key derivation and encryption still resolve to JDK providers`() {
        // These calls make sshd register its providers first, as the first connection does.
        SecurityUtils.isEDDSACurveSupported()
        forSshd()

        assertEquals("SunEC", KeyPairGenerator.getInstance("Ed25519").provider.name)
        assertEquals("SunJCE", KDF.getInstance("HKDF-SHA256").providerName)
        assertEquals("SunJCE", Cipher.getInstance("AES/GCM/NoPadding").provider.name)
    }

    @Test
    fun `a JDK key pair converts to sshd's EdDSA key types and keeps its key material`() {
        val jdk = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val converted = SshConnections.forSshd(jdk)

        assertTrue(SecurityUtils.getEDDSAPublicKeyType().isInstance(converted.public))
        assertTrue(SecurityUtils.getEDDSAPrivateKeyType().isInstance(converted.private))
        assertEquals("ssh-ed25519", KeyUtils.getKeyType(converted.public))
        // The JDK public key verifies a signature of the converted private key.
        val signer = SecurityUtils.getEDDSASigner().apply { initSigner(null, converted.private) }
        val data = "folio".toByteArray()
        signer.update(null, data)
        val signature = Signature.getInstance("Ed25519").apply { initVerify(jdk.public) }
        signature.update(data)
        assertTrue(signature.verify(signer.sign(null)))
        assertEquals(jdk.public.encoded.toList(), converted.public.encoded.toList())
    }

    @Test
    fun `a converted public key equals the same key parsed from its OpenSSH line`() {
        val converted = forSshd()
        val line = PublicKeyEntry.toString(converted.public)

        val parsed =
            PublicKeyEntry
                .parsePublicKeyEntry(
                    line,
                ).resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.FAILING)

        assertTrue(KeyUtils.compareKeys(converted.public, parsed))
    }

    private fun forSshd() = SshConnections.forSshd(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
}
