package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.git.internal.Synchronizer
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.GitIntegrationTest
import io.github.big_sw_little_sw.testing.SshGitServer
import io.github.big_sw_little_sw.testing.TestAudit
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCredentials
import io.github.big_sw_little_sw.testing.TestGit
import io.github.big_sw_little_sw.testing.andExpectForbidden
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.json
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These tests check the manual sync and the sync state of the Git source route (design 10.6, 12.2). Most credentials
 * name a Git instance that the configuration does not have, so a sync fails at once with `GIT_INSTANCE_NOT_CONFIGURED`.
 */
@GitIntegrationTest
class SyncApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val audit: TestAudit,
    @Autowired private val synchronizer: Synchronizer,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `a manual sync makes the ConfigSet due now and gives 202 with the sync state`() {
        val configSet = configSet()
        git.parkAll()

        mvc.post(syncRoute(configSet)) { bearer(SUPER_ADMIN) }.andExpect {
            status { isAccepted() }
            jsonPath("$.configSetId") { value(configSet.toApiId()) }
            jsonPath("$.repositoryPath") { value("repos/configs.git") }
            jsonPath("$.nextDueAt") { exists() }
            jsonPath("$.syncing") { value(false) }
            jsonPath("$.failureCode") { value(null) }
            jsonPath("$.consecutiveFailures") { value(0) }
        }

        val lease = synchronizer.claim(CLAIM_LIMIT).single()
        assertEquals(configSet, lease.configSet)
        assertTrue(synchronizer.sync(lease))
        val record = audit.records(configSet.value).single { it["operation"] == "SYNC_REQUESTED" }
        assertEquals("GIT_SOURCE", record["resource_type"])
        assertEquals("root", record["actor_subject"])
    }

    /** The request is the operation of the caller, so each request writes a record (design 10.6, 14). */
    @Test
    fun `each manual sync request writes SYNC_REQUESTED, also when the ConfigSet is already due`() {
        val namespace = catalog.namespace()
        val configSet = configSet(namespace)
        catalog.rule(configSet, Action.MANAGE, "user:alice")
        val path = mvc.get(gitRoute(configSet)) { bearer(SUPER_ADMIN) }.json("$.path")

        repeat(2) { mvc.post(syncRoute(configSet)) { bearer(alice) }.andExpect { status { isAccepted() } } }

        val records = audit.records(configSet.value).filter { it["operation"] == "SYNC_REQUESTED" }
        assertEquals(2, records.size)
        records.forEach {
            assertEquals("GIT_SOURCE", it["resource_type"])
            assertEquals(configSet.value, it["resource_id"])
            assertEquals(path, it["path"])
            assertEquals("AUTHENTICATED", it["actor_type"])
            assertEquals("alice", it["actor_subject"])
        }
    }

    @Test
    fun `a manual sync of a ConfigSet without a Git source fails with GIT_SOURCE_NOT_FOUND and records nothing`() {
        val configSet = catalog.configSet(catalog.namespace())

        mvc.post(syncRoute(configSet)) { bearer(SUPER_ADMIN) }.andExpectProblem(404, "GIT_SOURCE_NOT_FOUND")

        assertTrue(audit.records(configSet.value).none { it["operation"] == "SYNC_REQUESTED" })
    }

    @Test
    fun `a manual sync needs MANAGE on the ConfigSet`() {
        val namespace = catalog.namespace()
        val configSet = configSet(namespace)
        catalog.rule(namespace, Action.READ, "user:alice")
        val id = configSet.toApiId()

        mvc.post(syncRoute(configSet)) { bearer(alice) }.andExpectForbidden(Action.MANAGE, id)
        mvc.post(syncRoute(configSet)).andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        mvc
            .post("/api/v1/admin/configsets/cfg_${"0".repeat(32)}/git:sync") { bearer(SUPER_ADMIN) }
            .andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
        assertTrue(audit.records(configSet.value).none { it["operation"] == "SYNC_REQUESTED" })

        catalog.rule(configSet, Action.MANAGE, "user:alice")
        mvc.post(syncRoute(configSet)) { bearer(alice) }.andExpect { status { isAccepted() } }
    }

    @Test
    fun `the Git source route gives the sync state after a failed sync`() {
        val configSet = configSet()

        git.sync(configSet)

        mvc.get("/api/v1/admin/configsets/${configSet.toApiId()}/git") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.failureCode") { value("GIT_INSTANCE_NOT_CONFIGURED") }
            jsonPath("$.failureSummary") { exists() }
            jsonPath("$.consecutiveFailures") { value(1) }
            jsonPath("$.lastAttemptAt") { exists() }
            jsonPath("$.lastSuccessAt") { value(null) }
            jsonPath("$.lastSeenCommit") { value(null) }
            jsonPath("$.lastPublishedCommit") { value(null) }
            jsonPath("$.syncing") { value(false) }
        }
    }

    /** During a lease, the ConfigSet is due at the lease end (design 10.6). */
    @Test
    fun `the Git source route shows a running sync, and the sync state after a success`() {
        val tip = SshGitServer.createRepository("configs").last()
        val configSet = git.configSet(git.authorizedCredential().id)
        git.parkAll()
        git.makeDue(configSet)
        val lease = synchronizer.claim(CLAIM_LIMIT).single()

        val nextDueAt =
            mvc
                .get(gitRoute(configSet)) { bearer(SUPER_ADMIN) }
                .andExpect { jsonPath("$.syncing") { value(true) } }
                .json("$.nextDueAt")
        assertEquals(lease.end.toInstant(), Instant.parse(nextDueAt))

        assertTrue(synchronizer.sync(lease))
        mvc.get(gitRoute(configSet)) { bearer(SUPER_ADMIN) }.andExpect {
            jsonPath("$.syncing") { value(false) }
            jsonPath("$.lastSeenCommit") { value(tip) }
            jsonPath("$.lastPublishedCommit") { value(tip) }
            jsonPath("$.lastSuccessAt") { exists() }
            jsonPath("$.failureCode") { value(null) }
            jsonPath("$.failureSummary") { value(null) }
            jsonPath("$.consecutiveFailures") { value(0) }
        }
    }

    private fun configSet(namespace: NamespaceId = catalog.namespace()) =
        git.configSet(credentials.credential(namespace, gitInstance = "nowhere").id, namespace = namespace)

    private fun gitRoute(configSet: ConfigSetId) = "/api/v1/admin/configsets/${configSet.toApiId()}/git"

    private fun syncRoute(configSet: ConfigSetId) = "${gitRoute(configSet)}:sync"

    private companion object {
        const val CLAIM_LIMIT = 10
    }
}
