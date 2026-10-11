package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.RevisionId
import io.github.big_sw_little_sw.folio.git.internal.FailureCode
import io.github.big_sw_little_sw.folio.git.internal.Fetcher
import io.github.big_sw_little_sw.folio.git.internal.GitProperties
import io.github.big_sw_little_sw.folio.git.internal.GitSourceRepository
import io.github.big_sw_little_sw.folio.git.internal.OnDemandFetcher
import io.github.big_sw_little_sw.folio.git.internal.OnDemandProperties
import io.github.big_sw_little_sw.folio.git.internal.RepositoryCache
import io.github.big_sw_little_sw.folio.git.internal.SyncProperties
import io.github.big_sw_little_sw.folio.git.internal.SyncRepository
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.micrometer.core.instrument.MeterRegistry
import org.eclipse.jgit.lib.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.*
import org.springframework.util.unit.DataSize
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.test.*

/**
 * These tests check the limits of the fetch on demand, mostly through the consumption API. A test that must keep a read
 * waiting holds the fetch lock of the ConfigSet. A read of the [probe] ConfigSet shows if a permit is free: it fails
 * with `UNREACHABLE` while a permit is free, and with `SOURCE_BUSY` when no permit is free. The context has the default
 * limit of two fetches on demand.
 *
 * The tests also check the meter `folio.content.fetches`, which counts each read that needs a fetch by its result.
 */
@GitIntegrationTest
@FreshGitRepository
class OnDemandFetchIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val instances: TestSyncInstances,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val gitSourceRepository: GitSourceRepository,
    @Autowired private val properties: GitProperties,
    @Autowired private val fetcher: Fetcher,
    @Autowired private val syncRepository: SyncRepository,
    @Autowired private val cache: RepositoryCache,
    @Autowired private val meters: MeterRegistry,
) {
    @Test
    fun `a read that fetches its commit counts as fetched, and the meters of sync do not change`() {
        val configSet = published()
        val fetched = fetches("fetched")
        val attempts = meters.total("folio.sync.attempts")
        val syncFetches = meters.get("folio.sync.fetch.duration").timer().count()

        read(configSet).andExpect { status { isOk() } }
        read(configSet).andExpect { status { isOk() } }

        assertEquals(1.0, fetched.added())
        assertEquals(attempts, meters.total("folio.sync.attempts"))
        assertEquals(syncFetches, meters.get("folio.sync.fetch.duration").timer().count())
    }

    @Test
    fun `a read waits a maximum of five seconds for another fetch of the ConfigSet, then fails with SOURCE_BUSY`() {
        val configSet = published()
        val busy = fetches("busy")

        val seconds =
            git.whileFetchLockHeld(configSet) {
                val started = System.nanoTime()
                read(configSet).andExpectProblem(503, "SOURCE_BUSY")
                Duration.ofNanos(System.nanoTime() - started).toSeconds()
            }

        assertTrue(seconds in LOCK_WAIT_SECONDS..<LOCK_WAIT_SECONDS + MARGIN_SECONDS, "took $seconds s")
        assertEquals(1.0, busy.added())
    }

    /**
     * Two reads of two ConfigSets fetch from a slow server, so each holds a permit while its fetch runs. The server log
     * shows when both fetches run. Then a read of a third ConfigSet fails at once. With a limit of one, the second
     * read would fail at once, and the log would never show two fetches.
     */
    @Test
    fun `above the limit of fetches on demand, a read that needs a fetch fails immediately with SOURCE_BUSY`() {
        val credential = git.authorizedCredential(command = SshGitServer.logged(LOG, SLOW_SECONDS)).id
        val slow = (1..2).map { git.configSet(credential).also(::publishWithoutSync) }
        val probe = probe()
        SshGitServer.clearLog(LOG)

        val readers = slow.map(::startRead)
        awaitUntil("The server gets two commands") { SshGitServer.loggedCommands(LOG) >= 2 }
        val started = System.nanoTime()
        read(probe).andExpectProblem(503, "SOURCE_BUSY").andExpect {
            header { string(HttpHeaders.RETRY_AFTER, "5") }
        }

        assertEquals(0L, Duration.ofNanos(System.nanoTime() - started).toSeconds())
        assertEquals(listOf(200, 200), readers.map { it.await() })
    }

    /** A read that waits for the fetch lock does not fetch yet, so it holds no permit. */
    @Test
    fun `a read that waits for another fetch of the ConfigSet holds no permit`() {
        val configSet = published()
        val probe = probe()
        val failed = fetches("failed")

        val readers =
            git.whileFetchLockHeld(configSet) {
                val readers = readersWaitingForLock(configSet)
                read(probe).andExpectProblem(502, "UNREACHABLE")
                readers
            }

        assertEquals(listOf(200, 200), readers.map { it.await() })
        assertEquals(1.0, failed.added())
    }

    /**
     * Two reads wait for the fetch lock, which a holder keeps. The holder fetches the branch, and the reads then find
     * the commit in the cache. The server log shows only the two commands of the holder: its ls-remote and its fetch.
     */
    @Test
    fun `a read that waited for another fetch examines the cache again and does not fetch`() {
        val configSet = git.configSet(git.authorizedCredential(command = SshGitServer.logged(LOG)).id)
        git.publishTip(configSet)
        git.dropCache(configSet)
        SshGitServer.clearLog(LOG)
        val fetched = fetches("fetched")

        val readers = git.whileFetchLockHeld(configSet, {
            git.fetchTip(configSet)
        }) { readersWaitingForLock(configSet) }

        assertEquals(listOf(200, 200), readers.map { it.await() })
        assertEquals(2, SshGitServer.loggedCommands(LOG))
        assertEquals(2.0, fetched.added())
    }

    @Test
    fun `a published commit that is missing after a fetch stays unavailable for one sync interval without a fetch`(
        commits: Commits,
    ) {
        val (configSet, third) = missingAfterForcePush()
        val second = commits.tip
        val missing = fetches("missing")

        read(configSet, third).andExpectProblem(404, "REVISION_UNAVAILABLE")
        read(configSet, third).andExpectProblem(404, "REVISION_UNAVAILABLE")

        assertEquals(2, SshGitServer.loggedCommands(LOG))
        assertEquals(2.0, missing.added())
        read(configSet, second).andExpect { status { isOk() } }
    }

    /**
     * With a sync interval of one second, Folio soon forgets the missing commit. Then the next read fetches the commit
     * again.
     */
    @Test
    fun `a missing commit is fetched again after one sync interval`() {
        val (configSet, third) = missingAfterForcePush()
        val interval = Duration.ofSeconds(1)
        val syncProperties = SyncProperties(interval, maxBackoff = interval)
        val onDemand =
            OnDemandFetcher(fetcher, cache, syncRepository, properties, OnDemandProperties(), syncProperties, meters)
        val source = checkNotNull(gitSourceRepository.find(configSet))
        val fetch = { onDemand.fetch(configSet, source, RevisionId(third), ObjectId.fromString(third)) }

        repeat(2) { assertFailsWith<ContentException.RevisionUnavailable> { fetch() } }
        assertEquals(2, SshGitServer.loggedCommands(LOG))

        SshGitServer.resetBranch("configs", third)
        val started = System.nanoTime()
        awaitUntil("The memory of the missing commit ends") { runCatching(fetch).isSuccess }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < MARGIN_SECONDS)
        assertEquals(4, SshGitServer.loggedCommands(LOG))
    }

    @Test
    fun `after a sync that failed with REPOSITORY_TOO_LARGE, a read that needs a fetch fails with that code`() {
        val configSet = git.configSet(git.authorizedCredential(command = SshGitServer.logged(LOG)).id)
        git.publishTip(configSet)
        git.sync(configSet, instances.synchronizer(properties.copy(maxRepositorySize = DataSize.ofBytes(1))))
        assertEquals(FailureCode.REPOSITORY_TOO_LARGE, git.state(configSet).failure)
        SshGitServer.clearLog(LOG)
        val tooLarge = fetches("too_large")

        read(configSet).andExpectProblem(502, "REPOSITORY_TOO_LARGE")

        assertEquals(0, SshGitServer.loggedCommands(LOG))
        assertEquals(1.0, tooLarge.added())
    }

    /**
     * The last sync succeeded, so the read fetches. The fetcher of this test has a size limit of 1 byte, so the fetch
     * finds the repository above the limit.
     */
    @Test
    fun `a fetch on demand that finds the repository too large counts as too_large`() {
        val configSet = git.connected()
        val tip = git.publishTip(configSet)
        git.dropCache(configSet)
        val tooSmall = properties.copy(maxRepositorySize = DataSize.ofBytes(1))
        val onDemand =
            OnDemandFetcher(
                instances.fetcher(tooSmall),
                cache,
                syncRepository,
                properties,
                OnDemandProperties(),
                SyncProperties(),
                meters,
            )
        val source = checkNotNull(gitSourceRepository.find(configSet))
        val tooLarge = fetches("too_large")
        val failed = fetches("failed")

        val failure =
            assertFailsWith<ContentException.SourceFailed> {
                onDemand.fetch(configSet, source, RevisionId(tip), ObjectId.fromString(tip))
            }

        assertEquals("REPOSITORY_TOO_LARGE", failure.code)
        assertEquals(listOf(1.0, 0.0), listOf(tooLarge, failed).map { it.added() })
    }

    /** A ConfigSet with a published revision that the cache of this instance does not have. */
    private fun published(): ConfigSetId {
        val configSet = git.connected()
        git.publishTip(configSet)
        git.dropCache(configSet)
        return configSet
    }

    /**
     * Publishes a third commit, then moves the branch back to the second commit and deletes the cached repository. Thus
     * the third commit is published, but a fetch no longer gets it. The server log is empty. Returns the third commit.
     */
    private fun missingAfterForcePush(): Pair<ConfigSetId, String> {
        val configSet = git.configSet(git.authorizedCredential(command = SshGitServer.logged(LOG)).id)
        val second = git.publishTip(configSet)
        SshGitServer.addCommit("configs", "v3")
        val third = git.publishTip(configSet)
        SshGitServer.resetBranch("configs", second)
        git.dropCache(configSet)
        SshGitServer.clearLog(LOG)
        return configSet to third
    }

    /** Publishes the tip without a sync, so the cache of this instance does not have it. */
    private fun publishWithoutSync(configSet: ConfigSetId) {
        gitSourceRepository.publish(configSet, ObjectId.fromString(SshGitServer.objectId("configs", "main")))
    }

    /**
     * A ConfigSet whose credential names the Git instance `unreachable`, a closed port. Its published commit is never
     * in the cache, so each read fetches, and the fetch fails at once with `UNREACHABLE`.
     */
    private fun probe(): ConfigSetId {
        val configSet = git.configSet(credentials.credential(gitInstance = "unreachable").id)
        publishWithoutSync(configSet)
        return configSet
    }

    /**
     * Starts two reads of [configSet] and returns when both wait for the fetch lock that another thread holds. A read
     * that waits for the lock holds no permit, so the stack of its thread is the only signal of the wait.
     */
    private fun readersWaitingForLock(configSet: ConfigSetId): List<Future<Int>> {
        val threads = ConcurrentLinkedQueue<Thread>()
        val readers = (1..2).map { startRead(configSet) { threads += Thread.currentThread() } }
        awaitUntil("The reads wait for the fetch lock") {
            threads.size == readers.size && threads.all(::waitsForFetchLock)
        }
        return readers
    }

    /**
     * Kotlin adds a hash to the JVM name of a function with a value-class parameter, for example
     * `withFetchLock-kPJ61RQ`. Thus the test compares only the start of the name.
     */
    private fun waitsForFetchLock(thread: Thread) = thread.state == Thread.State.TIMED_WAITING &&
        thread.stackTrace.any { it.methodName.startsWith("withFetchLock") }

    /** Starts a read of [configSet] on its own thread, which first runs [started]. The future gives the status. */
    private fun startRead(configSet: ConfigSetId, started: () -> Unit = {}): Future<Int> {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val status =
            executor.submit<Int> {
                started()
                read(configSet).andReturn().response.status
            }
        // After the shutdown, the executor ends its thread when the read ends. A close would wait for the read, so the
        // read would not run in parallel.
        executor.shutdown()
        return status
    }

    private fun fetches(result: String) = CounterGrowth(meters, "folio.content.fetches", "result", result)

    private fun read(configSet: ConfigSetId, revision: String? = null) =
        mvc.get("/api/v1/configsets/${configSet.toApiId()}/files/app.yaml?revision=${revision.orEmpty()}") {
            bearer(SUPER_ADMIN)
        }

    private companion object {
        const val LOCK_WAIT_SECONDS = 5L
        const val MARGIN_SECONDS = 5L
        const val SLOW_SECONDS = 3
        const val LOG = "/tmp/on-demand.log"
    }
}
