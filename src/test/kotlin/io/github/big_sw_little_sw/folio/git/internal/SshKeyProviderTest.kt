package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.credential.internal.PrivateKeyCipher
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.util.security.SecurityUtils
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KDF
import kotlin.test.*

/**
 * Apache sshd registers Bouncy Castle for its Ed25519 keys. Folio's own crypto must still use the JDK providers.
 * Without these tests, a provider change could move Folio's crypto to Bouncy Castle without notice.
 */
class SshKeyProviderTest {
    @Test
    fun `sshd supports Ed25519`() {
        assertTrue(SecurityUtils.isEDDSACurveSupported())
    }

    @Test
    fun `Folio's own crypto still resolves to the JDK providers after sshd registers its providers`() {
        // These calls make sshd register its providers first, as the first connection does.
        SecurityUtils.isEDDSACurveSupported()
        forSshd()

        assertEquals("SUN", Security.getProviders().first().name)
        assertEquals("SunEC", KeyPairGenerator.getInstance("Ed25519").provider.name)
        assertEquals("SunEC", Signature.getInstance("Ed25519").provider.name)
        assertEquals("SunJCE", KDF.getInstance(PrivateKeyCipher.KDF_ALGORITHM).providerName)
        assertEquals("SunJCE", Cipher.getInstance(PrivateKeyCipher.TRANSFORMATION).provider.name)
    }

    @Test
    fun `a JDK key pair converts to sshd's EdDSA key types and keeps its key material`() {
        val jdk = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val converted = IsolatedSsh.forSshd(jdk)

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

    private fun forSshd() = IsolatedSsh.forSshd(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
}
