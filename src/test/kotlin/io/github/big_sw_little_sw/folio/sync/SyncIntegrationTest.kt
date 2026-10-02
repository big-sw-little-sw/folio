package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SshGitServer
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sync outcomes against a real SSH Git server (ADR 0004); the test config's interval is one hour. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SyncIntegrationTest(
    @Autowired private val synchronizer: Synchronizer,
    @Autowired private val states: SyncStateRepository,
    @Autowired private val configSets: ConfigSetService,
    @Autowired private val credentials: CredentialService,
    @Autowired namespaces: NamespaceService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val fixture = SyncFixture(configSets, credentials, namespaces, jdbc, "sync")
    private lateinit var commits: List<String>
    private lateinit var credential: Credential

    @BeforeEach
    fun setUp() {
        commits = fixture.reset()
        credential = fixture.authorizedCredential()
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `a new ConfigSet has never synced and is due at once`() {
        val configSet = fixture.configSet(credential)

        val state = state(configSet)
        assertNull(state.lastSyncedRevision)
        assertNull(state.lastAttemptAt)
        assertEquals(listOf(configSet.id), synchronizer.claimDue(10).map { it.configSetId })
    }

    @Test
    fun `the first sync records the branch tip as the seen and synced revision`() {
        val configSet = fixture.configSet(credential)

        syncDue()

        val state = state(configSet)
        assertEquals(commits.last(), state.lastSeenRevision)
        assertEquals(commits.last(), state.lastSyncedRevision)
        assertNotNull(state.lastSuccessAt)
        assertEquals(state.lastAttemptAt, state.lastSuccessAt)
        assertNull(state.errorCode)
        assertEquals(0, state.consecutiveFailures)
        assertEquals(Duration.ofHours(1), delay(state))
    }

    @Test
    fun `the next sync detects a new commit on the branch`() {
        val configSet = fixture.configSet(credential)
        syncDue()

        val third = SshGitServer.addCommit("sync", "v3")
        fixture.makeDue(configSet)
        syncDue()

        assertEquals(third, state(configSet).lastSyncedRevision)
    }

    @Test
    fun `a sync without changes records a successful attempt and keeps the revision`() {
        val configSet = fixture.configSet(credential)
        syncDue()
        val first = state(configSet)

        fixture.makeDue(configSet)
        syncDue()

        val second = state(configSet)
        assertEquals(first.lastSyncedRevision, second.lastSyncedRevision)
        assertTrue(checkNotNull(second.lastSuccessAt) > checkNotNull(first.lastSuccessAt))
    }

    @Test
    fun `a failure records its code, summary and count, keeps the revision and backs off`() {
        val configSet = fixture.configSet(credential)
        syncDue()
        SshGitServer.revokeAll()

        fixture.makeDue(configSet)
        syncDue()

        val once = state(configSet)
        assertEquals("AUTH_FAILED", once.errorCode)
        assertEquals(SourceFailure.AUTH_FAILED.summary, once.errorSummary)
        assertEquals(1, once.consecutiveFailures)
        assertEquals(commits.last(), once.lastSyncedRevision)
        assertTrue(checkNotNull(once.lastAttemptAt) > checkNotNull(once.lastSuccessAt))
        assertEquals(Duration.ofHours(2), delay(once))

        fixture.makeDue(configSet)
        syncDue()

        val twice = state(configSet)
        assertEquals(2, twice.consecutiveFailures)
        assertEquals(Duration.ofHours(4), delay(twice))
    }

    @Test
    fun `a success after failures clears the error and the count`() {
        val configSet = fixture.configSet(credential)
        SshGitServer.revokeAll()
        syncDue()
        assertEquals(1, state(configSet).consecutiveFailures)

        fixture.authorize(credential)
        fixture.makeDue(configSet)
        syncDue()

        val state = state(configSet)
        assertNull(state.errorCode)
        assertNull(state.errorSummary)
        assertEquals(0, state.consecutiveFailures)
        assertEquals(commits.last(), state.lastSyncedRevision)
        assertEquals(Duration.ofHours(1), delay(state))
    }

    @Test
    fun `a deleted branch is a failure, and the last synced revision stays`() {
        val configSet = fixture.configSet(credential)
        syncDue()

        SshGitServer.deleteMain("sync")
        fixture.makeDue(configSet)
        syncDue()

        val state = state(configSet)
        assertEquals("BRANCH_NOT_FOUND", state.errorCode)
        assertEquals(commits.last(), state.lastSyncedRevision)
        assertEquals(commits.last(), state.lastSeenRevision)
    }

    @Test
    fun `an unusable credential is a failure code, and other ConfigSets still sync`() {
        val disabled = fixture.configSet(fixture.authorizedCredential())
        credentials.disable(disabled.source.credentialId)
        val unconfigured = fixture.configSet(fixture.authorizedCredential())
        jdbc
            .sql("update credential set git_instance = 'removed' where id = :id")
            .param("id", unconfigured.source.credentialId.value)
            .update()
        val healthy = fixture.configSet(credential)

        syncDue()

        assertEquals("CREDENTIAL_DISABLED", state(disabled).errorCode)
        assertEquals("GIT_INSTANCE_NOT_CONFIGURED", state(unconfigured).errorCode)
        assertEquals(commits.last(), state(healthy).lastSyncedRevision)
    }

    @Test
    fun `a ConfigSet deleted after its claim is skipped`() {
        val configSet = fixture.configSet(credential)
        val lease = synchronizer.claimDue(10).single()

        configSets.delete(configSet.id)

        assertFalse(synchronizer.sync(lease))
    }

    private fun syncDue() {
        synchronizer.claimDue(MAX_CLAIMS).forEach { synchronizer.sync(it) }
    }

    private fun state(configSet: ConfigSet) = checkNotNull(states.find(configSet.id))

    private fun delay(state: SyncState) = Duration.between(state.lastAttemptAt, state.nextDueAt)

    companion object {
        const val MAX_CLAIMS = 100

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
