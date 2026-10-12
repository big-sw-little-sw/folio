package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.credential.CredentialKeys
import io.github.big_sw_little_sw.testing.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.util.unit.DataSize
import java.time.Duration
import kotlin.io.path.exists
import kotlin.test.*

/**
 * These tests check the fetch deadline, the size limit and the fetch lock. Each test uses a [Fetcher] with tighter
 * limits than the configuration. The deadline tests measure the wall clock, because a fetch must end at its deadline,
 * whatever the server does.
 */
@GitIntegrationTest
@FreshGitRepository
class FetchLimitsIntegrationTest(
    @Autowired private val git: TestGit,
    @Autowired private val instances: GitInstances,
    @Autowired private val keys: CredentialKeys,
    @Autowired private val ssh: IsolatedSsh,
    @Autowired private val cache: RepositoryCache,
    @Autowired private val properties: GitProperties,
) {
    @Test
    fun `an ls-remote on a server that sends nothing is cut at the deadline`() {
        val configSet = git.configSet(git.authorizedCredential(command = SshGitServer.delayed(SLOW_SECONDS)).id)

        val (failure, seconds) = timed { fetch(configSet, properties.copy(fetchDeadline = DEADLINE)) }

        assertEquals(FailureCode.DEADLINE_EXCEEDED, failure.code)
        assertTrue(seconds < DEADLINE.seconds + MARGIN_SECONDS, "took $seconds s")
    }

    @Test
    fun `an ls-remote on a server that sends data continuously is cut at the deadline`() {
        // At 8 bytes each second, the ref advertisement alone takes about 40 seconds. The connection is never idle.
        val configSet = git.configSet(git.authorizedCredential(command = SshGitServer.throttled(8)).id)

        val (failure, seconds) = timed { fetch(configSet, properties.copy(fetchDeadline = DEADLINE)) }

        assertEquals(FailureCode.DEADLINE_EXCEEDED, failure.code)
        assertTrue(seconds < DEADLINE.seconds + MARGIN_SECONDS, "took $seconds s")
    }

    @Test
    fun `a pack transfer that runs at the deadline is cut at the deadline`() {
        // The small ref advertisement arrives at once, so the ls-remote ends. The pack of 4 MB needs about a minute.
        SshGitServer.createLargeRepository("large", LARGE_REPOSITORY_BYTES)
        val credential = git.authorizedCredential(command = SshGitServer.throttled(THROTTLED_BYTES_PER_SECOND))
        val configSet = git.configSet(credential.id, repository = "large")

        val (failure, seconds) = timed { fetch(configSet, properties.copy(fetchDeadline = DEADLINE)) }

        assertEquals(FailureCode.DEADLINE_EXCEEDED, failure.code)
        assertTrue(seconds < DEADLINE.seconds + MARGIN_SECONDS, "took $seconds s")
        // Only the fetch creates the cache repository, after the ls-remote. Thus the deadline stopped the transfer.
        assertTrue(properties.cacheDirectory.resolve(configSet.value.toString()).exists())
    }

    @Test
    fun `a repository larger than the limit fails with REPOSITORY_TOO_LARGE, and Folio deletes it`() {
        val configSet = git.connected()

        val failure = fetch(configSet, properties.copy(maxRepositorySize = DataSize.ofBytes(1)))

        assertEquals(FailureCode.REPOSITORY_TOO_LARGE, failure.code)
        assertFalse(properties.cacheDirectory.resolve(configSet.value.toString()).exists())
    }

    /**
     * A read holds the repository, as a slow download does. The delete after the size check waits only until the
     * deadline, so the fetch still ends in time with REPOSITORY_TOO_LARGE. The repository stays until the next fetch
     * deletes it, and a new read does not wait behind the delete.
     */
    @Test
    fun `a repository larger than the limit that a read holds fails in time, and stays until a later fetch`() {
        val configSet = git.connected()
        git.publishTip(configSet)
        val repository = properties.cacheDirectory.resolve(configSet.value.toString())

        whileHolding({ held -> cache.reading(configSet, {}) { held() } }) {
            val limits = properties.copy(fetchDeadline = SIZE_CHECK_DEADLINE, maxRepositorySize = DataSize.ofBytes(1))
            val (failure, seconds) = timed { fetch(configSet, limits) }

            assertEquals(FailureCode.REPOSITORY_TOO_LARGE, failure.code)
            assertTrue(seconds < SIZE_CHECK_DEADLINE.seconds + MARGIN_SECONDS, "took $seconds s")
            assertTrue(repository.exists())
            assertEquals("read", cache.reading(configSet, { "missing" }) { "read" })
        }
        fetch(configSet, properties.copy(maxRepositorySize = DataSize.ofBytes(1)))
        assertFalse(repository.exists())
    }

    /**
     * Another fetch of the same ConfigSet holds the lock for the full deadline. The fetch fails at its deadline, and
     * the server got no command from it, because Folio takes the lock before the ls-remote. A fetch of another
     * ConfigSet does not wait.
     */
    @Test
    fun `a fetch waits for the fetch lock of its ConfigSet, and the deadline includes the wait`() {
        val credential = git.authorizedCredential(command = SshGitServer.logged(LOG))
        val configSet = git.configSet(credential.id)
        val other = git.configSet(credential.id)
        SshGitServer.clearLog(LOG)

        whileHolding({ held -> cache.withFetchLock(configSet, Duration.ofMinutes(1)) { held() } }) {
            val failure = fetch(configSet, properties.copy(fetchDeadline = Duration.ofSeconds(1)))

            assertEquals(FailureCode.DEADLINE_EXCEEDED, failure.code)
            assertEquals(0, SshGitServer.loggedCommands(LOG))
            git.publishTip(other)
            assertEquals(2, SshGitServer.loggedCommands(LOG))
        }
    }

    private fun fetch(configSet: ConfigSetId, limits: GitProperties): GitFailure = assertFailsWith<GitFailure> {
        git.fetchTip(configSet, Fetcher(keys, instances, cache, ssh, limits))
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val result = block()
        return result to Duration.ofNanos(System.nanoTime() - started).seconds
    }

    private companion object {
        val DEADLINE: Duration = Duration.ofSeconds(3)

        /** This deadline gives the fetch itself enough time to end, so the size check runs. */
        val SIZE_CHECK_DEADLINE: Duration = Duration.ofSeconds(10)

        /**
         * The margin covers the connect, the cut and slow CI machines. It is still far below the 40 seconds or more
         * that each server needs without the cut.
         */
        const val MARGIN_SECONDS = 12L

        /** Without the deadline, a fetch waits this long two times: for the ls-remote and for the fetch. */
        const val SLOW_SECONDS = 60
        const val LARGE_REPOSITORY_BYTES = 4_000_000
        const val THROTTLED_BYTES_PER_SECOND = 65_536
        const val LOG = "/tmp/fetch-lock.log"
    }
}
