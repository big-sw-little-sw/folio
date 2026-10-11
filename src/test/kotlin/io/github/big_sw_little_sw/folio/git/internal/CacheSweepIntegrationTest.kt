package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.testing.GitIntegrationTest
import io.github.big_sw_little_sw.testing.SshGitServer
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestGit
import io.github.big_sw_little_sw.testing.bearer
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests check the cache sweep (design 10.5). The sweep deletes the repositories of ConfigSets that no longer
 * exist. It keeps the repositories of ConfigSets that exist, the SSH home directory, other entries and the targets of
 * symbolic links. It does not wait for a read or a fetch, and a later sweep deletes the repository. The schedule runs
 * the sweep at each sync interval with [SyncRepository.existing].
 */
@GitIntegrationTest
class CacheSweepIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val cache: RepositoryCache,
    @Autowired private val syncs: SyncRepository,
    @Autowired private val properties: GitProperties,
) {
    private val root: Path get() = properties.cacheDirectory

    @BeforeEach
    fun setUp() {
        SshGitServer.revokeAll()
        SshGitServer.createRepository("configs")
    }

    @Test
    fun `the sweep deletes the repository of a deleted ConfigSet and keeps the repository of an existing one`() {
        val credential = git.authorizedCredential().id
        val kept = git.configSet(credential)
        val deleted = git.configSet(credential)
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

    /** The sweep shares one scheduler thread with the poll, so it must not wait for a slow read (design 10.5). */
    @Test
    fun `the sweep returns at once while a read holds the repository, and a later sweep deletes it`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        git.publishTip(configSet)
        delete(configSet)

        whileReading(configSet) { assertSkipped(configSet) }

        sweep()
        assertFalse(repositoryOf(configSet).exists())
    }

    @Test
    fun `the sweep skips a repository that a fetch holds, and a later sweep deletes it`() {
        val configSet = git.configSet(git.authorizedCredential().id)
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

    /** Runs [block] while another thread reads the repository of [configSet]. */
    private fun whileReading(configSet: ConfigSetId, block: () -> Unit) {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            executor.execute {
                cache.reading(configSet, {}) {
                    reading.countDown()
                    release.await(WAIT.seconds, TimeUnit.SECONDS)
                }
            }
            check(reading.await(WAIT.seconds, TimeUnit.SECONDS)) { "The read did not start" }
            try {
                block()
            } finally {
                release.countDown()
            }
        }
    }

    private fun delete(configSet: ConfigSetId) {
        mvc.delete("/api/v1/admin/configsets/${configSet.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            status { isNoContent() }
        }
    }

    private fun sweep() {
        cache.sweep(syncs::existing)
    }

    private fun repositoryOf(configSet: ConfigSetId): Path = root.resolve(configSet.value.toString())

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
    }
}
