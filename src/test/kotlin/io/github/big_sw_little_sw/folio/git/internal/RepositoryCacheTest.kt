package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.testing.*
import org.eclipse.jgit.lib.Repository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.*

/** These tests check the repository cache of one Folio instance and its fetch lock. */
class RepositoryCacheTest {
    @TempDir
    lateinit var root: Path

    private val cache by lazy { RepositoryCache(GitProperties(root.resolve("cache"))) }
    private val branch = Branch("main")

    @Test
    fun `only the owner can access the cache directory`() {
        cache.size(newId())

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("cache"))))
    }

    @Test
    fun `a fetch waits for the fetch lock of its ConfigSet, and gives up after the wait`() {
        val id = newId()

        whileHolding({ held -> cache.withFetchLock(id, WAIT) { held() } }) {
            assertNull(cache.withFetchLock(id, Duration.ZERO) { "ran" })
            assertEquals("ran", cache.withFetchLock(newId(), Duration.ZERO) { "ran" })
        }
    }

    @Test
    fun `a fetch runs with the automatic gc of JGit off`() {
        val id = newId()

        val (fetchAutoGc, gcAuto) =
            checkNotNull(
                cache.withFetchLock(id, WAIT) {
                    cache.fetching(id, branch, WAIT) {
                        it.config.getBoolean("fetch", "autogc", true) to it.config.getInt("gc", "auto", -1)
                    }
                },
            )

        assertFalse(fetchAutoGc)
        assertEquals(0, gcAuto)
    }

    @Test
    fun `a fetch without the fetch lock fails`() {
        assertFailsWith<IllegalStateException> { cache.fetching(newId(), branch, WAIT) {} }
    }

    @Test
    fun `the size counts the files of the repository, and a missing repository has size zero`() {
        val id = newId()
        assertEquals(0, cache.size(id))
        fetch(id) {}
        val before = cache.size(id)

        root.resolve("cache/${id.value}/extra").writeText("x".repeat(1000))

        assertEquals(before + 1000, cache.size(id))
    }

    @Test
    fun `a delete does not follow symbolic links`() {
        val outside = root.resolve("outside").createDirectories()
        outside.resolve("keep").writeText("keep")
        val id = newId()
        fetch(id) {}
        val repository = root.resolve("cache/${id.value}")
        Files.createSymbolicLink(repository.resolve("link"), outside)

        assertTrue(cache.delete(id, WAIT))

        assertFalse(repository.exists())
        assertTrue(outside.resolve("keep").exists())
        assertEquals("missing", cache.reading(id, { "missing" }) { "found" })
    }

    /** A repository is damaged when a ref names an object that the repository does not have. */
    @Test
    fun `a fetch makes a damaged repository again, empty`() {
        val id = newId()
        fetch(id) {}
        val repository = root.resolve("cache/${id.value}")
        repository.resolve("refs/heads/main").writeText("${"1".repeat(40)}\n")
        repository.resolve("marker").writeText("old")

        val (tip, objectsExist) = fetch(id) { it.exactRef(branch.ref) to it.objectDatabase.exists() }

        assertNull(tip)
        assertTrue(objectsExist)
        assertFalse(repository.resolve("marker").exists())
    }

    /**
     * A read holds the repository, for example while a slow client downloads a file. A new start of the repository
     * waits only for the time that its caller gives. After that time, new reads do not wait behind it.
     */
    @Test
    fun `a new start of a repository that a read holds fails with DEADLINE_EXCEEDED after the wait`() {
        val id = newId()

        whileHolding({ held -> cache.reading(id, { held() }) { held() } }) {
            val failure = assertFailsWith<GitFailure> { fetch(id, SHORT_WAIT) {} }

            assertEquals(FailureCode.DEADLINE_EXCEEDED, failure.code)
            assertEquals("missing", cache.reading(id, { "missing" }) { "found" })
        }
    }

    @Test
    fun `a delete of a repository that a read holds gives up after the wait, and the repository stays`() {
        val id = newId()
        fetch(id) {}

        whileHolding({ held -> cache.reading(id, { held() }) { held() } }) {
            assertFalse(cache.delete(id, SHORT_WAIT))

            assertEquals("found", cache.reading(id, { "missing" }) { "found" })
        }
        assertTrue(cache.delete(id, WAIT))
    }

    /** Runs [block] in a fetch of the ConfigSet. A new start of the repository waits a maximum of [wait] for reads. */
    private fun <T : Any> fetch(id: ConfigSetId, wait: Duration = WAIT, block: (Repository) -> T): T =
        checkNotNull(cache.withFetchLock(id, WAIT) { cache.fetching(id, branch, wait, block) })

    private fun newId() = ConfigSetId(UUID.randomUUID())

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(1)
        val SHORT_WAIT: Duration = Duration.ofMillis(100)
    }
}
