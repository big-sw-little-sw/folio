package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.git.GitException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** These tests check the validation of the values of a Source definition (design 10.2). */
class GitSourceDefinitionTest {
    @Test
    fun `a repository path has segments of letters, digits, dots, underscores and hyphens`() {
        listOf("org/repo.git", "repo", "a/b/c_d-e.git", "x".repeat(500)).forEach { RepositoryPath(it) }

        listOf(
            "",
            "/org/repo",
            "org/repo/",
            "org//repo",
            "../repo",
            "org/./repo",
            "ssh://host/repo",
            "git@host:repo",
            "org/re po",
            "x".repeat(501),
        ).forEach { assertFailsWith<GitException.InvalidRepositoryPath>(it) { RepositoryPath(it) } }
    }

    @Test
    fun `a branch is a valid Git ref name without refs-heads`() {
        assertEquals("refs/heads/release/1.x", Branch("release/1.x").ref)
        Branch("x".repeat(250))

        listOf("", "a..b", "a b", "a~1", "a^", "a:b", "a.lock", "a/", "@{", "x".repeat(251))
            .forEach { assertFailsWith<GitException.InvalidBranch>(it) { Branch(it) } }
    }

    @Test
    fun `a root path is empty or has the normal form of an item path`() {
        assertEquals("app.yaml", RootPath("").resolve(ItemPath("app.yaml")))
        assertEquals("config/sub/app.yaml", RootPath("config/sub").resolve(ItemPath("app.yaml")))
        RootPath("x".repeat(1000))

        listOf("/config", "config/", "a//b", "..", "a/../b", "./a", "a\\b", "a\u0000b", "x".repeat(1001))
            .forEach { assertFailsWith<GitException.InvalidRootPath>(it) { RootPath(it) } }
    }
}
