package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.ConfigSetSources
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPairs
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.source.internal.RepositoryCache
import io.github.big_sw_little_sw.folio.source.internal.SourceProperties
import io.github.big_sw_little_sw.folio.source.internal.SshConnections
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cap on concurrent fetches and the fetch deadline (ADR 0033), against a server that answers each command two
 * seconds late. A sync there takes at least four seconds: ls-remote, then the fetch.
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SyncBoundsIntegrationTest(
    @Autowired private val synchronizer: Synchronizer,
    @Autowired private val states: SyncStateRepository,
    @Autowired private val configSetSources: ConfigSetSources,
    @Autowired private val properties: SyncProperties,
    @Autowired private val keyPairs: CredentialKeyPairs,
    @Autowired private val connections: SshConnections,
    @Autowired private val cache: RepositoryCache,
    @Autowired private val sourceProperties: SourceProperties,
    @Autowired configSets: ConfigSetService,
    @Autowired credentials: CredentialService,
    @Autowired namespaces: NamespaceService,
    @Autowired jdbc: JdbcClient,
) {
    private val fixture = SyncFixture(configSets, credentials, namespaces, jdbc, "bounds")

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
        val credential = fixture.authorizedCredential(SERVER_DELAY_SECONDS)
        val configSets = (1..3).map { fixture.configSet(credential) }
        val poller = SyncPoller(synchronizer, properties.copy(maxConcurrentFetches = 2))
        try {
            poller.poll()
            assertEquals(2, fixture.leasedCount())

            // Both slots are still busy with the slow server.
            poller.poll()
            assertEquals(2, fixture.leasedCount())

            awaitNoLeases()
            poller.poll()
            assertEquals(1, fixture.leasedCount())
            awaitNoLeases()
        } finally {
            poller.destroy()
        }
        assertTrue(configSets.all { checkNotNull(states.find(it.id)).lastSyncedRevision != null })
    }

    @Test
    fun `a fetch still running at the deadline is aborted and recorded as such`() {
        val configSet = fixture.configSet(fixture.authorizedCredential(SERVER_DELAY_SECONDS))
        val impatient =
            SourceAccess(keyPairs, connections, cache, sourceProperties.copy(fetchDeadline = Duration.ofSeconds(1)))
        val impatientSync = Synchronizer(states, configSetSources, impatient, properties)

        assertTrue(impatientSync.sync(impatientSync.claimDue(1).single()))

        val state = checkNotNull(states.find(configSet.id))
        assertEquals("DEADLINE_EXCEEDED", state.errorCode)
        assertEquals(SourceFailure.DEADLINE_EXCEEDED.summary, state.errorSummary)
    }

    /** Waits for the poller's fetches to finish; a generous bound, as fetches end on their own. */
    private fun awaitNoLeases() {
        val end = System.nanoTime() + Duration.ofSeconds(AWAIT_SECONDS).toNanos()
        while (fixture.leasedCount() > 0) {
            check(System.nanoTime() - end < 0) { "Fetches did not finish" }
            Thread.sleep(AWAIT_STEP_MILLIS)
        }
    }

    companion object {
        const val SERVER_DELAY_SECONDS = 2
        const val AWAIT_SECONDS = 60L
        const val AWAIT_STEP_MILLIS = 100L

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
