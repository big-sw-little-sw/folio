package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.Slug
import org.apache.sshd.common.config.keys.PublicKeyEntry
import java.nio.file.Path
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests check the Git instances of the configuration (design 10.1). An entry that is not valid stops the start of
 * Folio, and no message shows a key.
 */
class GitInstancesTest {
    private val hostKey =
        PublicKeyEntry.toString(
            SshConnections.forSshd(KeyPairGenerator.getInstance("Ed25519").generateKeyPair()).public,
        )

    @Test
    fun `an instance gets the default port and user, and its host keys`() {
        val instances = instances("github" to GitProperties.Instance("github.example.com", hostKeys = listOf(hostKey)))

        val github = checkNotNull(instances.find(Slug("github")))
        assertEquals(22, github.port)
        assertEquals("git", github.user)
        assertEquals(1, github.hostKeys.size)
        assertNull(instances.find(Slug("other")))
    }

    @Test
    fun `the SSH URL comes from the instance and the repository path, and an IPv6 host gets brackets`() {
        val instances =
            instances(
                "github" to GitProperties.Instance("github.example.com", 2222, "u", listOf(hostKey)),
                "ipv6" to GitProperties.Instance("::1", hostKeys = listOf(hostKey)),
            )
        val github = checkNotNull(instances.find(Slug("github")))
        val ipv6 = checkNotNull(instances.find(Slug("ipv6")))

        assertEquals("ssh://u@github.example.com:2222/org/repo.git", github.url(RepositoryPath("org/repo.git")))
        assertEquals("ssh://git@[::1]:22/org/repo.git", ipv6.url(RepositoryPath("org/repo.git")))
    }

    @Test
    fun `a host key line that does not parse names its property and never shows the line`() {
        val secretLooking = "ssh-ed25519 AAAAnotakey"
        val instance = GitProperties.Instance("h", hostKeys = listOf(hostKey, secretLooking))

        val failure = assertFailsWith<IllegalArgumentException> { instances("github" to instance) }

        assertEquals("folio.git.instances.github.host-keys[1] is not an OpenSSH public key line", failure.message)
        assertFalse(secretLooking in failure.message.orEmpty())
    }

    @Test
    fun `an instance without host keys, with a bad name, host, port or user stops the start`() {
        val failures =
            listOf(
                "github" to GitProperties.Instance("h"),
                "GitHub" to GitProperties.Instance("h", hostKeys = listOf(hostKey)),
                "github" to GitProperties.Instance("evil@host", hostKeys = listOf(hostKey)),
                "github" to GitProperties.Instance("h", port = 0, hostKeys = listOf(hostKey)),
                "github" to GitProperties.Instance("h", user = "a/b", hostKeys = listOf(hostKey)),
            ).map { (name, instance) ->
                assertFailsWith<IllegalArgumentException> { instances(name to instance) }
            }

        assertEquals("folio.git.instances.github.host-keys must have one or more lines", failures.first().message)
        failures.forEach { assertTrue(it.message.orEmpty().startsWith("folio.git.instances."), it.message) }
    }

    private fun instances(vararg entries: Pair<String, GitProperties.Instance>) =
        GitInstances(GitProperties(Path.of("/tmp/folio-unused"), instances = entries.toMap()))
}
