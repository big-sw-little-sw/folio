package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.internal.GitProperties.Instance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitPropertiesTest {
    private val hostKeys = listOf("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea")

    @Test
    fun `the SSH port defaults to 22 and the user to git`() {
        val instance = Instance("github.com", hostKeys = hostKeys)

        assertEquals(22, instance.port)
        assertEquals("git", instance.user)
    }

    @Test
    fun `instance names are lowercase letters, digits and single hyphens`() {
        GitProperties(mapOf("gitlab-eu-1" to Instance("gitlab.example.com", hostKeys = hostKeys)))

        listOf("", "GitHub", "git.example.com", "a--b", "-a").forEach { name ->
            assertFailsWith<IllegalArgumentException> {
                GitProperties(mapOf(name to Instance("example.com", hostKeys = hostKeys)))
            }
        }
    }

    @Test
    fun `every instance needs a trusted host key, and the message names the instance`() {
        val failure =
            assertFailsWith<IllegalArgumentException> { GitProperties(mapOf("github" to Instance("github.com"))) }

        assertEquals("folio.git.instances.github.host-keys must list at least one trusted host key", failure.message)
    }

    @Test
    fun `accepts host names and IP addresses`() {
        listOf("github.com", "10.0.0.1", "::1", "fe80::1").forEach { Instance(it, hostKeys = hostKeys) }
    }

    @Test
    fun `rejects hosts and users that would change the SSH URL, and ports outside 1 to 65535`() {
        listOf(" ", "", "evil@github.com", "github.com/x", "[::1]").forEach {
            assertFailsWith<IllegalArgumentException> { Instance(it, hostKeys = hostKeys) }
        }
        listOf("", "a@b", "a:b", "a/b").forEach {
            assertFailsWith<IllegalArgumentException> { Instance("example.com", user = it, hostKeys = hostKeys) }
        }
        assertFailsWith<IllegalArgumentException> { Instance("example.com", 0) }
        assertFailsWith<IllegalArgumentException> { Instance("example.com", 65536) }
    }
}
