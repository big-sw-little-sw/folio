package io.github.big_sw_little_sw.folio.git.internal

import org.springframework.util.unit.DataSize
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

/**
 * These tests check the settings under `folio.git`. A value that is not valid stops the start of Folio, and the message
 * names the property.
 */
class GitPropertiesTest {
    private val cacheDirectory = Path.of("/var/cache/folio")

    @Test
    fun `the cache directory must be absolute`() {
        val failure = assertFailsWith<IllegalArgumentException> { GitProperties(Path.of("relative/dir")) }

        assertEquals("folio.git.cache-directory must be an absolute path", failure.message)
    }

    @Test
    fun `the fetch deadline and the maximum repository size must be positive`() {
        val deadline =
            assertFailsWith<IllegalArgumentException> { GitProperties(cacheDirectory, fetchDeadline = Duration.ZERO) }
        val size =
            assertFailsWith<IllegalArgumentException> {
                GitProperties(cacheDirectory, maxRepositorySize = DataSize.ofBytes(0))
            }

        assertEquals("folio.git.fetch-deadline must be positive", deadline.message)
        assertEquals("folio.git.max-repository-size must be positive", size.message)
    }
}
