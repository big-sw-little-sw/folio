package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.ConfigSetSources
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SshGitServer
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Leases across instances: each [Synchronizer] is one instance, with its own lease owner ID (ADR 0032). */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SyncLeaseIntegrationTest(
    @Autowired private val synchronizer: Synchronizer,
    @Autowired private val states: SyncStateRepository,
    @Autowired private val configSetSources: ConfigSetSources,
    @Autowired private val sources: SourceAccess,
    @Autowired private val properties: SyncProperties,
    @Autowired configSets: ConfigSetService,
    @Autowired credentials: CredentialService,
    @Autowired namespaces: NamespaceService,
    @Autowired jdbc: JdbcClient,
) {
    private val fixture = SyncFixture(configSets, credentials, namespaces, jdbc, "leases")
    private val other = Synchronizer(states, configSetSources, sources, properties)
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
    fun `concurrent claims by two instances never lease the same ConfigSet`() {
        val credential = fixture.authorizedCredential()
        val ids = (1..CONFIG_SETS).map { fixture.configSet(credential).id }.toSet()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures =
            listOf(synchronizer, other).map { instance ->
                pool.submit(
                    Callable {
                        start.await()
                        instance.claimDue(CONFIG_SETS)
                    },
                )
            }
        start.countDown()
        val claims = futures.map { future -> future.get().map { it.configSetId }.toSet() }
        pool.shutdown()

        assertEquals(emptySet(), claims[0] intersect claims[1])
        assertEquals(ids, claims[0] + claims[1])
        assertEquals(emptyList(), synchronizer.claimDue(CONFIG_SETS))
        assertEquals(emptyList(), other.claimDue(CONFIG_SETS))
    }

    @Test
    fun `an expired lease can be claimed by another instance`() {
        val configSet = fixture.configSet(fixture.authorizedCredential())
        synchronizer.claimDue(1).single()
        assertEquals(emptyList(), other.claimDue(1))

        fixture.expireLease(configSet)

        assertEquals(other.instanceId, other.claimDue(1).single().owner)
        assertEquals(other.instanceId.toString(), fixture.leaseOwner(configSet))
    }

    @Test
    fun `an instance that lost its lease records nothing, and the new holder records its result`() {
        val configSet = fixture.configSet(fixture.authorizedCredential())
        val lost = synchronizer.claimDue(1).single()
        fixture.expireLease(configSet)
        val taken = other.claimDue(1).single()

        assertFalse(synchronizer.sync(lost))
        assertNull(checkNotNull(states.find(configSet.id)).lastAttemptAt)
        assertEquals(other.instanceId.toString(), fixture.leaseOwner(configSet))

        assertTrue(other.sync(taken))
        assertEquals(commits.last(), checkNotNull(states.find(configSet.id)).lastSyncedRevision)
        assertNull(fixture.leaseOwner(configSet))
    }

    @Test
    fun `a lease claimed again by the same instance replaces the earlier one`() {
        val configSet = fixture.configSet(fixture.authorizedCredential())
        val earlier = synchronizer.claimDue(1).single()
        fixture.expireLease(configSet)
        val later = synchronizer.claimDue(1).single()

        assertFalse(synchronizer.sync(earlier))
        assertTrue(synchronizer.sync(later))
    }

    @Test
    fun `a manual request during a sync makes the ConfigSet due again once the sync is recorded`() {
        val configSet = fixture.configSet(fixture.authorizedCredential())
        val lease = synchronizer.claimDue(1).single()

        states.markDue(configSet.id)
        assertEquals(emptyList(), other.claimDue(1))
        assertTrue(synchronizer.sync(lease))

        assertEquals(configSet.id, other.claimDue(1).single().configSetId)
    }

    companion object {
        const val CONFIG_SETS = 20

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
