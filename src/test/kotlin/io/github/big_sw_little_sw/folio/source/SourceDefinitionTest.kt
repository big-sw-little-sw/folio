package io.github.big_sw_little_sw.folio.source

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceDefinitionTest {
    @ParameterizedTest
    @ValueSource(strings = ["org/repo.git", "repo", "group/sub-group/my_repo.v2", "repos/a.git"])
    fun `accepts repository paths of plain segments`(path: String) {
        assertEquals(path, RepositoryPath(path).value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "/org/repo.git", "org/repo.git/", "org//repo.git", "../repo.git", "org/./repo.git",
            "git@github.com:org/repo.git", "ssh://github.com/org/repo.git", "~/repo.git", "org/repo .git",
            "org\\repo.git",
        ],
    )
    fun `rejects repository paths that are absolute, traverse, or carry a host, scheme or user`(path: String) {
        assertFailsWith<InvalidRepositoryPathException> { RepositoryPath(path) }
    }

    @Test
    fun `a branch maps to its ref under refs heads`() {
        assertEquals("refs/heads/release/1.0", Branch("release/1.0").ref)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "a b", "a..b", "a~1", "a^", "a:b", "/main", "main/", "a//b", "main.lock", "@{u}"])
    fun `rejects names that are not valid branch names`(name: String) {
        assertFailsWith<InvalidBranchException> { Branch(name) }
    }

    @Test
    fun `an exact revision is a full lowercase commit ID`() {
        RevisionRef.Commit("0123456789abcdef0123456789abcdef01234567")

        listOf("", "0123456", "0123456789ABCDEF0123456789ABCDEF01234567", "main", "HEAD~1").forEach {
            assertFailsWith<InvalidCommitIdException> { RevisionRef.Commit(it) }
        }
    }
}
