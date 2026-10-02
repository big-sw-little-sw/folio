package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.credential.GitInstance
import org.apache.sshd.common.config.keys.KeyUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedHostKeysTest {
    @Test
    fun `parses OpenSSH public key lines with or without a comment`() {
        val keys = TrustedHostKeys.parse(instance(ED25519, "$ED25519 root@server", ECDSA))

        assertEquals(listOf("ssh-ed25519", "ssh-ed25519", "ecdsa-sha2-nistp256"), keys.map { KeyUtils.getKeyType(it) })
        assertTrue(KeyUtils.compareKeys(keys[0], keys[1]))
        assertFalse(KeyUtils.compareKeys(keys[0], keys[2]))
    }

    @Test
    fun `a bad line fails with a message naming the instance and position but not the key`() {
        val bad =
            listOf(
                "not a key",
                "ssh-ed25519",
                "ssh-ed25519 not-base64!",
                "ssh-rsa ${ED25519.substringAfter(' ')}",
                "github.com $ED25519",
                "",
            )
        bad.forEach { line ->
            val failure = assertFailsWith<IllegalArgumentException> { TrustedHostKeys.parse(instance(ED25519, line)) }

            assertEquals("folio.git.instances.test.host-keys[1] is not an OpenSSH public key line", failure.message)
        }
    }

    private fun instance(vararg hostKeys: String) = GitInstance("test", "example.com", 22, "git", hostKeys.toList())

    private companion object {
        // RFC 8032 section 7.1, test 1, as in OpenSshPublicKeyTest.
        const val ED25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea"

        // Generated with `ssh-keygen -t ecdsa -b 256`.
        const val ECDSA =
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBH4Y8IAg6se7Lokc8ZckRDwhoTYQdOB4" +
                "fJabxDybMAafH5BeDpQzPPOH2r3uoScXuHPzZgqHtNbsEPY/+JyHXb4="
    }
}
