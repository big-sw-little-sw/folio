package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.policy.PermissionDeniedException
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.sync.SyncFixture
import io.github.big_sw_little_sw.folio.sync.SyncService
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Audit records of sync requests and of sync outcomes, which are recorded only when they change (ADR 0038). */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SyncAuditIntegrationTest(
    @Autowired private val synchronizer: Synchronizer,
    @Autowired private val sync: SyncService,
    @Autowired configSets: ConfigSetService,
    @Autowired credentials: CredentialService,
    @Autowired namespaces: NamespaceService,
    @Autowired jdbc: JdbcClient,
) {
    private val fixture = SyncFixture(configSets, credentials, namespaces, jdbc, "sync-audit")
    private val records = AuditRecords(jdbc)
    private lateinit var commits: List<String>
    private lateinit var credential: Credential
    private lateinit var configSet: ConfigSet

    @BeforeEach
    fun setUp() {
        commits = fixture.reset()
        credential = fixture.authorizedCredential()
        configSet = fixture.configSet(credential)
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `sync outcomes are recorded only when they change, by the system`() {
        syncNow()
        syncNow()
        val third = SshGitServer.addCommit("sync-audit", "v3")
        syncNow()
        SshGitServer.revokeAll()
        syncNow()
        syncNow()
        SshGitServer.deleteMain("sync-audit")
        fixture.authorize(credential)
        syncNow()
        SshGitServer.resetMain("sync-audit", commits.last())
        syncNow()

        val rows = outcomes()
        assertEquals(
            listOf(
                "SYNC_REVISION_CHANGED" to mapOf("revision" to commits.last(), "previousRevision" to null),
                "SYNC_REVISION_CHANGED" to mapOf("revision" to third, "previousRevision" to commits.last()),
                "SYNC_FAILED" to mapOf("failureCode" to "AUTH_FAILED", "previousFailureCode" to null),
                "SYNC_FAILED" to mapOf("failureCode" to "BRANCH_NOT_FOUND", "previousFailureCode" to "AUTH_FAILED"),
                "SYNC_RECOVERED" to recovered(commits.last(), third, "BRANCH_NOT_FOUND"),
            ),
            rows.map { it.action to it.details },
        )
        assertEquals(
            setOf(listOf("SYSTEM", null, "production/${configSet.slug.value}")),
            rows.map { listOf(it.actorType, it.actorSubject, it.path) }.toSet(),
        )
    }

    @Test
    fun `a manual sync request is recorded with its caller, a denied one is not`() {
        authenticateAs("alice")
        assertFailsWith<PermissionDeniedException> { sync.request(configSet.id) }

        authenticateAs(SUPER_ADMIN)
        sync.request(configSet.id)

        val requests = records.of(configSet.id.value).filter { it.action == "SYNC_REQUESTED" }
        assertEquals(1, requests.size)
        assertEquals(SUPER_ADMIN, requests.single().actorSubject)
        assertEquals("production/${configSet.slug.value}", requests.single().path)
    }

    private fun recovered(
        revision: String,
        previousRevision: String,
        previousFailureCode: String,
    ) = mapOf(
        "revision" to revision,
        "previousRevision" to previousRevision,
        "previousFailureCode" to previousFailureCode,
    )

    private fun syncNow() {
        fixture.makeDue(configSet)
        synchronizer.claimDue(MAX_CLAIMS).forEach { synchronizer.sync(it) }
    }

    private fun outcomes() = records.of(configSet.id.value).filter { it.action.startsWith("SYNC_") }

    companion object {
        private const val MAX_CLAIMS = 100

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
