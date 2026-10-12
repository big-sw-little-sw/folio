package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.testing.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import kotlin.test.*

/**
 * These tests check the poller: the fetch semaphore, the running syncs, unexpected errors and the shutdown. Each test
 * makes a poller of its own and runs its polls. A test that must keep a sync running holds the fetch lock of the
 * ConfigSet, so the sync waits and the result does not depend on timing.
 */
@GitIntegrationTest
@FreshGitRepository
@ExtendWith(OutputCaptureExtension::class)
class SyncPollerIntegrationTest(
    @Autowired private val synchronizer: Synchronizer,
    @Autowired private val git: TestGit,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val properties: SyncProperties,
    @Autowired private val jdbc: JdbcClient,
    @Autowired instances: TestSyncInstances,
) {
    private val other = instances.synchronizer()

    @BeforeEach
    fun setUp() {
        git.parkAll()
    }

    @Test
    fun `a poll syncs each due ConfigSet`() {
        val credential = git.authorizedCredential().id
        val configSets = listOf(git.configSet(credential), git.configSet(credential))
        val poller = SyncPoller(synchronizer, properties)

        poller.poll()

        assertTrue(poller.awaitSyncs(TIMEOUT))
        configSets.forEach {
            assertNotNull(git.state(it).lastSuccessAt)
            assertNull(git.leaseOwner(it))
        }
    }

    @Test
    fun `a poll claims no more ConfigSets than the free permits of the fetch semaphore`() {
        val credential = git.authorizedCredential().id
        val first = git.configSet(credential)
        val second = git.configSet(credential)
        val poller = SyncPoller(synchronizer, properties.copy(maxConcurrentFetches = 1))

        git.whileFetchLockHeld(first) {
            poller.poll()
            poller.poll()

            assertEquals(synchronizer.owner, git.leaseOwner(first))
            assertNull(git.leaseOwner(second))
        }
        assertTrue(poller.awaitSyncs(TIMEOUT))
    }

    /**
     * The lease of a running sync ends, so the ConfigSet is due again. The poller of the instance that runs the sync
     * does not claim it. Another instance can claim it, and the first sync then records nothing.
     */
    @Test
    fun `a poll never claims a ConfigSet that the instance syncs now`() {
        val configSet = git.connected()
        val poller = SyncPoller(synchronizer, properties)

        val taken =
            git.whileFetchLockHeld(configSet) {
                poller.poll()
                git.expireLease(configSet)
                poller.poll()

                assertEquals(synchronizer.owner, git.leaseOwner(configSet))
                assertTrue(git.isDue(configSet))
                other.claim(1).single()
            }

        assertTrue(poller.awaitSyncs(TIMEOUT))
        assertNull(git.state(configSet).lastAttemptAt)
        assertTrue(other.sync(taken))
    }

    /**
     * The key does not decrypt, so the credential module throws an `IllegalStateException`. The poller has one permit,
     * so a second sync shows that the permit came back. The message names the key, so it must not reach the log.
     */
    @Test
    fun `an unexpected error releases the lease, records no failure and logs the ConfigSet and the exception type`(
        output: CapturedOutput,
    ) {
        val credential = git.authorizedCredential()
        val configSet = git.configSet(credential.id)
        credentials.corruptActiveKey(credential.id)
        val poller = SyncPoller(synchronizer, properties.copy(maxConcurrentFetches = 1))

        poller.poll()

        assertTrue(poller.awaitSyncs(TIMEOUT))
        assertNull(git.leaseOwner(configSet))
        val state = git.state(configSet)
        assertNull(state.lastAttemptAt)
        assertNull(state.failure)
        val untilDue = secondsUntilDue(configSet)
        assertTrue(untilDue > INTERVAL_SECONDS - MARGIN_SECONDS && untilDue <= INTERVAL_SECONDS, "$untilDue s")
        val line =
            "The sync of ConfigSet ${configSet.toApiId()} failed unexpectedly. Exception types: " +
                "IllegalStateException"
        assertTrue(line in output.all, output.all)
        assertFalse("does not decrypt" in output.all, output.all)

        val next = git.connected()
        poller.poll()
        assertTrue(poller.awaitSyncs(TIMEOUT))
        assertNotNull(git.state(next).lastSuccessAt)
    }

    /**
     * The stop waits ten seconds for the sync, which waits for the fetch lock. Then the stop releases the lease and
     * interrupts the sync. The holder keeps the lock, so only the interrupt can end the sync. A shutdown is normal, so
     * the poller logs no error.
     */
    @Test
    fun `a stop waits ten seconds, then releases the leases of the running syncs and interrupts them`(
        output: CapturedOutput,
    ) {
        val configSet = git.connected()
        val poller = SyncPoller(synchronizer, properties)

        val seconds =
            git.whileFetchLockHeld(configSet) {
                poller.poll()
                val started = System.nanoTime()
                poller.stop()
                poller.poll()
                val seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds()
                assertTrue(poller.awaitSyncs(Duration.ofSeconds(MARGIN_SECONDS)))
                seconds
            }

        assertTrue(seconds in SHUTDOWN_SECONDS..<SHUTDOWN_SECONDS + MARGIN_SECONDS, "took $seconds s")
        assertNull(git.leaseOwner(configSet))
        assertTrue(git.isDue(configSet))
        assertNull(git.state(configSet).lastAttemptAt)
        val id = configSet.toApiId()
        assertTrue("The sync of ConfigSet $id stopped at shutdown" in output.all, output.all)
        assertFalse("The sync of ConfigSet $id failed unexpectedly" in output.all, output.all)
        assertFalse("The release of the lease of ConfigSet $id failed" in output.all, output.all)
    }

    /** The release sets the due time with the database clock, so the test reads the time with the same clock. */
    private fun secondsUntilDue(configSet: ConfigSetId): Double = jdbc
        .sql("select extract(epoch from next_due_at - now())::float8 from git_source where config_set_id = :id")
        .param("id", configSet.value)
        .query(Double::class.java)
        .single()

    private companion object {
        const val INTERVAL_SECONDS = 60L
        const val SHUTDOWN_SECONDS = 10L
        const val MARGIN_SECONDS = 5L
    }
}
