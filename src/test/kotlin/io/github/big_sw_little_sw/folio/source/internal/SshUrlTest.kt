package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.credential.GitInstance
import io.github.big_sw_little_sw.folio.source.RepositoryPath
import kotlin.test.Test
import kotlin.test.assertEquals

class SshUrlTest {
    @Test
    fun `builds the URL from the instance's user, host and port and the repository path`() {
        assertEquals(
            "ssh://git@github.com:22/org/repo.git",
            sshUrl(instance("github.com", 22, "git"), RepositoryPath("org/repo.git")),
        )
        assertEquals(
            "ssh://bot@git.example.com:2222/team/configs",
            sshUrl(instance("git.example.com", 2222, "bot"), RepositoryPath("team/configs")),
        )
    }

    @Test
    fun `puts IPv6 addresses in brackets`() {
        assertEquals("ssh://git@[::1]:22/repo.git", sshUrl(instance("::1", 22, "git"), RepositoryPath("repo.git")))
    }

    private fun instance(
        host: String,
        port: Int,
        user: String,
    ) = GitInstance("test", host, port, user, emptyList())
}
