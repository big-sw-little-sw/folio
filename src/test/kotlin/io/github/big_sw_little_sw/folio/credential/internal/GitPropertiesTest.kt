package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.internal.GitProperties.GitInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitPropertiesTest {
    @Test
    fun `the SSH port defaults to 22`() {
        assertEquals(22, GitInstance("github.com").port)
    }

    @Test
    fun `instance names are lowercase letters, digits and single hyphens`() {
        GitProperties(mapOf("gitlab-eu-1" to GitInstance("gitlab.example.com")))

        listOf("", "GitHub", "git.example.com", "a--b", "-a").forEach { name ->
            assertFailsWith<IllegalArgumentException> { GitProperties(mapOf(name to GitInstance("example.com"))) }
        }
    }

    @Test
    fun `rejects a blank host and ports outside 1 to 65535`() {
        assertFailsWith<IllegalArgumentException> { GitInstance(" ") }
        assertFailsWith<IllegalArgumentException> { GitInstance("example.com", 0) }
        assertFailsWith<IllegalArgumentException> { GitInstance("example.com", 65536) }
    }
}
