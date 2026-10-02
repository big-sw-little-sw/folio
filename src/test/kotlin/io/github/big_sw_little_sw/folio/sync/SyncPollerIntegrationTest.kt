package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.ConfigSetSources
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.sync.internal.SyncLease
import io.github.big_sw_little_sw.folio.sync.internal.SyncPoller
import io.github.big_sw_little_sw.folio.sync.internal.SyncProperties
import io.github.big_sw_little_sw.folio.sync.internal.SyncStateRepository
import io.github.big_sw_little_sw.folio.sync.internal.Synchronizer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The poller's fetch slots, its own running syncs and shutdown (ADR 0032), against a server that answers late. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SyncPollerIntegrationTest(
    @Autowired private val synchronizer: Synchronizer,
    @Autowired private val states: SyncStateRepository,
    @Autowired private val properties: SyncProperties,
    @Autowired private val configSetSources: ConfigSetSources,
    @Autowired private val sources: SourceAccess,
    @Autowired configSets: ConfigSetService,
    @Autowired credentials: CredentialService,
    @Autowired namespaces: NamespaceService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val fixture = SyncFixture(configSets, credentials, namespaces, jdbc, "poller")

    @BeforeEach
    fun setUp() {
        fixture.reset()
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `a poll claims no more ConfigSets than there are free fetch slots`() {
        val credential = fixture.authorizedCredential(SshGitServer.delayed(2))
        val configSets = (1..3).map { fixture.configSet(credential) }
        val poller = SyncPoller(synchronizer, properties.copy(maxConcurrentFetches = 2))
        try {
            poller.poll()
            assertEquals(2, fixture.leasedCount())

            // Both slots are still busy: each sync waits four seconds for the server.
            poller.poll()
            assertEquals(2, fixture.leasedCount())

            awaitLeasedCount(0)
            poller.poll()
            assertEquals(1, fixture.leasedCount())
            awaitLeasedCount(0)
        } finally {
            poller.destroy()
        }
        assertTrue(configSets.all { checkNotNull(states.find(it.id)).lastSyncedRevision != null })
    }

    @Test
    fun `a poll does not claim a ConfigSet this instance is still syncing, even once its lease has expired`() {
        val configSet = fixture.configSet(fixture.authorizedCredential(SshGitServer.delayed(3)))
        val poller = SyncPoller(synchronizer, properties)
        try {
            poller.poll()
            fixture.expireLease(configSet)

            poller.poll()

            val reclaimed =
                jdbc
                    .sql("select lease_until > now() from sync_state where config_set_id = :id")
                    .param("id", configSet.id.value)
                    .query(Boolean::class.java)
                    .single()
            assertEquals(false, reclaimed)
        } finally {
            poller.destroy()
        }
    }

    @Test
    fun `a sync that fails and cannot even release its lease still returns its slot`() {
        val configSet = fixture.configSet(fixture.authorizedCredential())
        val broken =
            object : Synchronizer(states, configSetSources, sources, properties) {
                override fun sync(lease: SyncLease): Boolean = throw IllegalStateException("sync failed")

                override fun release(
                    lease: SyncLease,
                    delay: Duration,
                ) = throw DataAccessResourceFailureException("release failed")
            }
        val poller = SyncPoller(broken, properties.copy(maxConcurrentFetches = 1))
        try {
            poller.poll()
            fixture.expireLease(configSet)

            // With the only slot lost, or the ConfigSet still counted as running, no later poll would claim it.
            val end = System.nanoTime() + Duration.ofSeconds(AWAIT_SECONDS).toNanos()
            while (!isLeased(configSet)) {
                check(System.nanoTime() - end < 0) { "The ConfigSet was never claimed again" }
                Thread.sleep(AWAIT_STEP_MILLIS)
                poller.poll()
            }
        } finally {
            poller.destroy()
        }
    }

    @Test
    fun `shutdown releases the leases of syncs still running, due at once and without a failure`() {
        val configSet = fixture.configSet(fixture.authorizedCredential(SshGitServer.delayed(SLOWER_THAN_SHUTDOWN)))
        val poller = SyncPoller(synchronizer, properties)
        poller.poll()

        poller.destroy()

        assertTrue(fixture.isDue(configSet))
        val state = checkNotNull(states.find(configSet.id))
        assertNull(state.lastAttemptAt)
        assertEquals(0, state.consecutiveFailures)
    }

    private fun isLeased(configSet: ConfigSet): Boolean =
        jdbc
            .sql("select lease_until > now() from sync_state where config_set_id = :id")
            .param("id", configSet.id.value)
            .query(Boolean::class.java)
            .single()

    /** Waits for the poller's syncs to reach [expected] leases; they end on their own, so the bound is generous. */
    private fun awaitLeasedCount(expected: Int) {
        val end = System.nanoTime() + Duration.ofSeconds(AWAIT_SECONDS).toNanos()
        while (fixture.leasedCount() != expected) {
            check(System.nanoTime() - end < 0) { "Leases did not reach $expected" }
            Thread.sleep(AWAIT_STEP_MILLIS)
        }
    }

    companion object {
        /** Longer than the poller's ten-second grace on shutdown. */
        const val SLOWER_THAN_SHUTDOWN = 15
        const val AWAIT_SECONDS = 60L
        const val AWAIT_STEP_MILLIS = 100L

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
