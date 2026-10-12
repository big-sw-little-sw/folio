package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.*

/** These tests run the cache sweep with [SyncRepository.existing], as the schedule does. */
@GitIntegrationTest
@FreshGitRepository
class CacheSweepIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val cache: RepositoryCache,
    @Autowired private val syncRepository: SyncRepository,
    @Autowired private val properties: GitProperties,
) {
    private val root: Path get() = properties.cacheDirectory

    @Test
    fun `the sweep deletes the repository of a deleted ConfigSet and keeps the repository of an existing one`() {
        val kept = git.connected()
        val deleted = git.connected()
        git.publishTip(kept)
        git.publishTip(deleted)
        delete(deleted)

        sweep()

        assertTrue(repositoryOf(kept).exists())
        assertFalse(repositoryOf(deleted).exists())
    }

    @Test
    fun `the sweep keeps the SSH home directory, entries that are not repositories and the targets of links`() {
        val unknown = root.resolve(UUID.randomUUID().toString()).createDirectories()
        val notes = root.resolve("notes").createDirectories()
        val file = root.resolve(UUID.randomUUID().toString()).also { it.writeText("x") }
        val target = Files.createTempDirectory("folio-sweep-target")
        target.resolve("keep.txt").writeText("keep")
        val link = Files.createSymbolicLink(root.resolve(UUID.randomUUID().toString()), target)

        sweep()

        assertFalse(unknown.exists())
        assertTrue(root.resolve("ssh-home").exists())
        assertTrue(notes.exists())
        assertTrue(file.exists())
        assertTrue(link.exists(LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(target.resolve("keep.txt")))
    }

    /** The sweep must not wait, because it runs on the scheduler thread of the polls ([SyncSchedule]). */
    @Test
    fun `the sweep returns at once while a read holds the repository, and a later sweep deletes it`() {
        val configSet = git.connected()
        git.publishTip(configSet)
        delete(configSet)

        whileHolding({ held -> cache.reading(configSet, {}) { held() } }) { assertSkipped(configSet) }

        sweep()
        assertFalse(repositoryOf(configSet).exists())
    }

    @Test
    fun `the sweep skips a repository that a fetch holds, and a later sweep deletes it`() {
        val configSet = git.connected()
        git.publishTip(configSet)
        delete(configSet)

        git.whileFetchLockHeld(configSet) { assertSkipped(configSet) }

        sweep()
        assertFalse(repositoryOf(configSet).exists())
    }

    private fun assertSkipped(configSet: ConfigSetId) {
        val started = System.nanoTime()
        sweep()
        val seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds()

        assertEquals(0L, seconds)
        assertTrue(repositoryOf(configSet).exists())
    }

    private fun delete(configSet: ConfigSetId) {
        mvc.delete("/api/v1/admin/configsets/${configSet.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            status { isNoContent() }
        }
    }

    private fun sweep() {
        cache.sweep(syncRepository::existing)
    }

    private fun repositoryOf(configSet: ConfigSetId): Path = root.resolve(configSet.value.toString())
}
