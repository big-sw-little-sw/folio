package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSet
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
import org.springframework.util.unit.DataSize
import java.time.Duration
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fetch deadline and the repository size limit (ADR 0033), through a [Synchronizer] whose [SourceAccess] has
 * tighter bounds than the test configuration. The deadline tests measure the wall clock: the point is that a fetch
 * ends at its deadline, whatever the server does.
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
    private lateinit var commits: List<String>

    @BeforeEach
    fun setUp() {
        commits = fixture.reset()
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `ls-remote on a server that sends nothing past the deadline is cut off at the deadline`() {
        val configSet = fixture.configSet(fixture.authorizedCredential(SshGitServer.delayed(SLOW_SECONDS)))

        val seconds = secondsToSync(sourceProperties.copy(fetchDeadline = DEADLINE))

        assertTrue(seconds < DEADLINE.seconds + MARGIN_SECONDS, "took $seconds s")
        assertEquals("DEADLINE_EXCEEDED", state(configSet).errorCode)
        assertEquals(SourceFailure.DEADLINE_EXCEEDED.summary, state(configSet).errorSummary)
    }

    @Test
    fun `ls-remote on a server that keeps sending, so the connection is never idle, is cut off at the deadline`() {
        // About 40 seconds for the ref advertisement alone.
        val configSet = fixture.configSet(fixture.authorizedCredential(SshGitServer.throttled(8)))

        val seconds = secondsToSync(sourceProperties.copy(fetchDeadline = DEADLINE))

        assertTrue(seconds < DEADLINE.seconds + MARGIN_SECONDS, "took $seconds s")
        assertEquals("DEADLINE_EXCEEDED", state(configSet).errorCode)
    }

    @Test
    fun `a pack transfer still running at the deadline is cut off at the deadline`() {
        // The ref advertisements pass at once, so ls-remote finishes; the 4 MB pack would take about a minute.
        SshGitServer.createLargeRepository("large", LARGE_REPOSITORY_BYTES)
        val credential = fixture.authorizedCredential(SshGitServer.throttled(THROTTLED_BYTES_PER_SECOND))
        val configSet = fixture.configSet(credential, repository = "large")

        val seconds = secondsToSync(sourceProperties.copy(fetchDeadline = DEADLINE))

        assertTrue(seconds < DEADLINE.seconds + MARGIN_SECONDS, "took $seconds s")
        assertEquals("DEADLINE_EXCEEDED", state(configSet).errorCode)
        // The fetch itself started: only it creates the cached repository, after ls-remote.
        assertTrue(sourceProperties.cacheDirectory.resolve("${configSet.id.value}.git").exists())
    }

    @Test
    fun `a repository larger than the limit is discarded, and the last synced revision stays`() {
        val configSet = fixture.configSet(fixture.authorizedCredential())
        synchronizer.sync(synchronizer.claimDue(1).single())
        fixture.makeDue(configSet)

        secondsToSync(sourceProperties.copy(maxRepositorySize = DataSize.ofBytes(1)))

        val state = state(configSet)
        assertEquals("REPOSITORY_TOO_LARGE", state.errorCode)
        assertEquals(SourceFailure.REPOSITORY_TOO_LARGE.summary, state.errorSummary)
        assertEquals(commits.last(), state.lastSyncedRevision)
        assertFalse(sourceProperties.cacheDirectory.resolve("${configSet.id.value}.git").exists())
    }

    /** Syncs the one due ConfigSet with [bounds] and returns how long it took, in whole seconds. */
    private fun secondsToSync(bounds: SourceProperties): Long {
        val bounded =
            Synchronizer(states, configSetSources, SourceAccess(keyPairs, connections, cache, bounds), properties)
        val lease = bounded.claimDue(1).single()
        val started = System.nanoTime()
        assertTrue(bounded.sync(lease))
        return Duration.ofNanos(System.nanoTime() - started).seconds
    }

    private fun state(configSet: ConfigSet) = checkNotNull(states.find(configSet.id))

    companion object {
        val DEADLINE: Duration = Duration.ofSeconds(3)

        /**
         * Covers connecting, the cut and recording, with headroom for slow CI machines; still well below the 40 seconds
         * or more each server would take without the cut.
         */
        const val MARGIN_SECONDS = 12L

        /** Without the deadline, a sync would wait this long twice, for ls-remote and the fetch. */
        const val SLOW_SECONDS = 60
        const val LARGE_REPOSITORY_BYTES = 4_000_000
        const val THROTTLED_BYTES_PER_SECOND = 65_536

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
