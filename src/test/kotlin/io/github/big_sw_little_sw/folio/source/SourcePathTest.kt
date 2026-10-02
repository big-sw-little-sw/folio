package io.github.big_sw_little_sw.folio.source

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourcePathTest {
    @ParameterizedTest
    @ValueSource(strings = ["config", "config/app.yaml", "a/b/c.json", ".hidden", "a..b", "with space/x", "..x"])
    fun `accepts normal relative paths unchanged`(text: String) {
        assertEquals(text, SourcePath.parse(text).value)
    }

    @Test
    fun `the empty path is the repository root`() {
        assertTrue(SourcePath.parse("").isRoot)
        assertEquals(SourcePath.ROOT, SourcePath.parse(""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // absolute
            "/", "/config", "//config",
            // traversal
            "..", "../config", "config/..", "config/../../etc/passwd", "a/../b",
            // current-directory segments
            ".", "./config", "config/.",
            // empty segments
            "config/", "config//app.yaml",
            // backslashes, which some file systems treat as separators
            "config\\app.yaml", "..\\config", "\\config",
            // NUL
            "config\u0000.yaml",
        ],
    )
    fun `rejects absolute paths, traversal, empty segments, backslashes and NUL`(text: String) {
        val failure = assertFailsWith<InvalidSourcePathException> { SourcePath.parse(text) }

        assertEquals(text, failure.value)
    }

    @Test
    fun `rejects paths longer than the column`() {
        SourcePath.parse("a".repeat(1000))

        assertFailsWith<InvalidSourcePathException> { SourcePath.parse("a".repeat(1001)) }
    }

    @Test
    fun `resolves a file beneath a root path`() {
        assertEquals("config/app.yaml", SourcePath.parse("config").resolve(SourcePath.parse("app.yaml")).value)
        assertEquals("app.yaml", SourcePath.ROOT.resolve(SourcePath.parse("app.yaml")).value)
        assertEquals("config", SourcePath.parse("config").resolve(SourcePath.ROOT).value)
    }
}
