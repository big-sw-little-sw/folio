package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.source.Branch
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryCacheTest {
    @TempDir
    lateinit var root: Path

    private val cache by lazy { RepositoryCache(SourceProperties(root)) }
    private val branch = Branch("main")

    @Test
    fun `waiting for the fetch lock that another fetch of the same ConfigSet holds gives up after the wait`() {
        val id = UUID.randomUUID()
        val holding = CountDownLatch(1)
        val done = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.execute {
                cache.withFetchLock(id, Duration.ofSeconds(1)) {
                    holding.countDown()
                    done.await()
                }
            }
            holding.await()

            assertNull(cache.withFetchLock(id, Duration.ofMillis(50)) { "ran" })
            assertEquals("ran", cache.withFetchLock(UUID.randomUUID(), Duration.ofMillis(50)) { "ran" })
        } finally {
            done.countDown()
            pool.shutdown()
        }
    }

    @Test
    fun `fetches run with JGit's automatic gc turned off`() {
        val id = UUID.randomUUID()

        val (fetchAutoGc, gcAuto) =
            checkNotNull(
                cache.withFetchLock(id, Duration.ofSeconds(1)) {
                    cache.fetching(id, branch) {
                        it.config.getBoolean("fetch", "autogc", true) to it.config.getInt("gc", "auto", -1)
                    }
                },
            )

        assertFalse(fetchAutoGc)
        assertEquals(0, gcAuto)
        assertTrue(
            root
                .resolve("$id.git/config")
                .toFile()
                .readText()
                .contains("autogc = false"),
        )
    }

    @Test
    fun `fetching without the fetch lock is refused`() {
        assertFailsWith<IllegalStateException> { cache.fetching(UUID.randomUUID(), branch) {} }
    }

    @Test
    fun `the size of a repository that does not exist is zero`() {
        assertEquals(0, cache.size(UUID.randomUUID()))
    }

    @Test
    fun `the size counts the repository's files`() {
        val id = UUID.randomUUID()
        cache.withFetchLock(id, Duration.ofSeconds(1)) { cache.fetching(id, branch) {} }
        val before = cache.size(id)

        root.resolve("$id.git").resolve("extra").writeText("x".repeat(1000))

        assertEquals(before + 1000, cache.size(id))
    }

    @Test
    fun `deleting a repository does not follow symbolic links, and links are never listed`() {
        val outside = root.resolve("outside").createDirectories()
        outside.resolve("keep").writeText("keep")
        val id = UUID.randomUUID()
        val repository = root.resolve("$id.git").createDirectories()
        Files.createSymbolicLink(repository.resolve("link"), outside)
        val linkedId = UUID.randomUUID()
        Files.createSymbolicLink(root.resolve("$linkedId.git"), outside)

        assertEquals(setOf(id), cache.ids())
        cache.delete(id)

        assertFalse(repository.exists())
        assertTrue(outside.resolve("keep").exists())
    }
}
