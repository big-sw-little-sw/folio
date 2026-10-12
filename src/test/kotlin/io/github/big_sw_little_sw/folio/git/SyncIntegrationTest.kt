package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.git.internal.FailureCode
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.web.servlet.*
import java.time.Duration
import kotlin.test.*

/**
 * These tests check one sync of a Git source: the change detection, the publish step, the sync state, the backoff and
 * the audit records of sync results. [TestGit.sync] claims and syncs one ConfigSet, as a poll does.
 */
@GitIntegrationTest
@FreshGitRepository
@ExtendWith(OutputCaptureExtension::class)
class SyncIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val audit: TestAudit,
) {
    @Test
    fun `a new ConfigSet is due at creation, and the first sync publishes the tip`(commits: Commits) {
        val configSet = git.connected()
        assertTrue(git.isDue(configSet))
        assertNull(git.state(configSet).lastAttemptAt)

        assertTrue(git.sync(configSet))

        val state = git.state(configSet)
        assertEquals(commits.tip, state.lastSeenCommit)
        assertNotNull(state.lastSuccessAt)
        assertNull(state.failure)
        assertEquals(INTERVAL_SECONDS, secondsToDue(configSet))
        latest(configSet, commits.tip)
        assertSyncRecords(
            configSet,
            "SYNC_REVISION_PUBLISHED" to """{"commitId": "${commits.tip}", "previousCommitId": null}""",
        )
        val record = syncRecords(configSet).single()
        assertEquals("SYSTEM", record["actor_type"])
        assertEquals("GIT_SOURCE", record["resource_type"])
    }

    @Test
    fun `a sync of the same tip publishes nothing, and a new commit publishes a new revision`() {
        val configSet = git.connected()
        val first = git.publishTip(configSet)

        assertTrue(git.sync(configSet))
        val second = SshGitServer.addCommit("configs", "v3")
        assertTrue(git.sync(configSet))

        latest(configSet, second)
        revisions(configSet).andExpect { jsonPath("$.revisions[*].id") { value(contains(second, first)) } }
        assertSyncRecords(
            configSet,
            "SYNC_REVISION_PUBLISHED" to """{"commitId": "$first", "previousCommitId": null}""",
            "SYNC_REVISION_PUBLISHED" to """{"commitId": "$second", "previousCommitId": "$first"}""",
        )
    }

    @Test
    fun `a deleted branch fails the sync with BRANCH_NOT_FOUND, and the last published commit stays`() {
        val configSet = git.connected()
        val tip = git.publishTip(configSet)

        SshGitServer.deleteBranch("configs")
        assertTrue(git.sync(configSet))

        assertEquals(FailureCode.BRANCH_NOT_FOUND, git.state(configSet).failure)
        assertEquals(tip, git.state(configSet).lastSeenCommit)
        latest(configSet, tip)
        mvc.get("/api/v1/configsets/${configSet.toApiId()}/files/app.yaml") { bearer(SUPER_ADMIN) }.andExpect {
            content { string("v2") }
        }
    }

    @Test
    fun `SYNC_FAILED is recorded only when the failure code changes, and SYNC_RECOVERED after the first success`() {
        val credential = git.authorizedCredential()
        val configSet = git.configSet(credential.id)
        SshGitServer.revokeAll()

        git.sync(configSet)
        git.sync(configSet)
        authorize(credential)
        SshGitServer.deleteBranch("configs")
        git.sync(configSet)
        assertEquals(3, git.state(configSet).consecutiveFailures)
        val tip = SshGitServer.createRepository("configs").last()
        git.sync(configSet)

        assertEquals(0, git.state(configSet).consecutiveFailures)
        assertSyncRecords(
            configSet,
            "SYNC_FAILED" to """{"code": "AUTH_FAILED", "previousCode": null}""",
            "SYNC_FAILED" to """{"code": "BRANCH_NOT_FOUND", "previousCode": "AUTH_FAILED"}""",
            "SYNC_RECOVERED" to """{"previousCode": "BRANCH_NOT_FOUND"}""",
            "SYNC_REVISION_PUBLISHED" to """{"commitId": "$tip", "previousCommitId": null}""",
        )
    }

    /** This test shows the common recovery: a failure after a publish, then a success with the same tip. */
    @Test
    fun `a recovery with an unchanged tip writes only SYNC_RECOVERED, and the revision list does not change`() {
        val credential = git.authorizedCredential()
        val configSet = git.configSet(credential.id)
        val tip = git.publishTip(configSet)
        val revisionsBefore = revisions(configSet).andReturn().response.contentAsString
        SshGitServer.revokeAll()

        git.sync(configSet)
        authorize(credential)
        git.sync(configSet)

        assertSyncRecords(
            configSet,
            "SYNC_REVISION_PUBLISHED" to """{"commitId": "$tip", "previousCommitId": null}""",
            "SYNC_FAILED" to """{"code": "AUTH_FAILED", "previousCode": null}""",
            "SYNC_RECOVERED" to """{"previousCode": "AUTH_FAILED"}""",
        )
        latest(configSet, tip)
        assertEquals(revisionsBefore, revisions(configSet).andReturn().response.contentAsString)
    }

    /**
     * The publish step refuses a tip after an earlier publish. The last seen commit is then the refused tip, and the
     * last published commit stays. Each sync of the refused tip runs the publish step again, but the code
     * does not change, so only one `SYNC_FAILED` record follows.
     */
    @Test
    fun `a refused tip keeps the last published commit, and a later valid tip publishes and recovers`() {
        val configSet = git.connected()
        val published = git.publishTip(configSet)
        val refused = SshGitServer.removeDirectory("configs", "config")

        git.sync(configSet)
        git.sync(configSet)

        val state = git.state(configSet)
        assertEquals(FailureCode.ROOT_PATH_NOT_FOUND, state.failure)
        assertEquals(2, state.consecutiveFailures)
        assertEquals(refused, state.lastSeenCommit)
        latest(configSet, published)
        mvc.get("/api/v1/admin/configsets/${configSet.toApiId()}/git") { bearer(SUPER_ADMIN) }.andExpect {
            jsonPath("$.lastSeenCommit") { value(refused) }
            jsonPath("$.lastPublishedCommit") { value(published) }
        }

        val restored = SshGitServer.addCommit("configs", "v4")
        git.sync(configSet)

        latest(configSet, restored)
        assertSyncRecords(
            configSet,
            "SYNC_REVISION_PUBLISHED" to """{"commitId": "$published", "previousCommitId": null}""",
            "SYNC_FAILED" to """{"code": "ROOT_PATH_NOT_FOUND", "previousCode": null}""",
            "SYNC_RECOVERED" to """{"previousCode": "ROOT_PATH_NOT_FOUND"}""",
            "SYNC_REVISION_PUBLISHED" to """{"commitId": "$restored", "previousCommitId": "$published"}""",
        )
    }

    @Test
    fun `each consecutive failure doubles the time to the next due time, and a success resets it`() {
        val credential = git.authorizedCredential()
        val configSet = git.configSet(credential.id)
        SshGitServer.revokeAll()

        val delays =
            (1..3).map {
                git.sync(configSet)
                secondsToDue(configSet)
            }
        authorize(credential)
        git.sync(configSet)

        assertEquals(listOf(2, 4, 8).map { it * INTERVAL_SECONDS }, delays)
        assertEquals(INTERVAL_SECONDS, secondsToDue(configSet))
    }

    @Test
    fun `a root path that is missing at the new tip fails the sync with ROOT_PATH_NOT_FOUND, and nothing publishes`(
        commits: Commits,
    ) {
        val configSet = git.configSet(git.authorizedCredential().id, rootPath = "missing")

        assertTrue(git.sync(configSet))

        assertEquals(FailureCode.ROOT_PATH_NOT_FOUND, git.state(configSet).failure)
        assertEquals(commits.tip, git.state(configSet).lastSeenCommit)
        revisions(configSet).andExpect { jsonPath("$.revisions") { isEmpty() } }
    }

    /** Trees that share subtrees name 100,000 files in a small pack. The publish step stops at the file limit. */
    @Test
    fun `a tip with too many files below the root path fails the sync with TOO_MANY_FILES, and nothing publishes`() {
        val commit = SshGitServer.createSharedTreeRepository("shared")
        val configSet = git.configSet(git.authorizedCredential().id, repository = "shared")

        assertTrue(git.sync(configSet))

        assertEquals(FailureCode.TOO_MANY_FILES, git.state(configSet).failure)
        assertEquals(commit, git.state(configSet).lastSeenCommit)
        revisions(configSet).andExpect { jsonPath("$.revisions") { isEmpty() } }
    }

    @Test
    fun `the publish step logs the number of skipped file names one time, and the file list does not log it`(
        output: CapturedOutput,
    ) {
        val configSet = git.connected()
        val line = "ConfigSet ${configSet.toApiId()} has 1 files that are not item paths"

        git.publishTip(configSet)
        mvc.get("/api/v1/configsets/${configSet.toApiId()}/files") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
        }
        git.sync(configSet)

        assertEquals(1, output.all.split(line).size - 1, output.all)
        // The repository controls the names, so the log never shows them.
        assertFalse("slash.txt" in output.all, output.all)
    }

    private fun authorize(credential: Credential) {
        SshGitServer.authorize(credential.activeKey.publicKeyLine)
    }

    private fun syncRecords(configSet: ConfigSetId) =
        audit.records(configSet.value).filter { (it["operation"] as String).startsWith("SYNC_") }

    /** Expects these sync records of [configSet], in this order: each operation with its JSON details. */
    private fun assertSyncRecords(configSet: ConfigSetId, vararg expected: Pair<String, String>) {
        assertEquals(
            expected.map { (operation, details) -> operation to audit.json(details) },
            syncRecords(configSet).map { it["operation"] to audit.json(it["details"]) },
        )
    }

    private fun latest(configSet: ConfigSetId, commit: String) {
        mvc.get("/api/v1/configsets/${configSet.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            jsonPath("$.latestRevision") { value(commit) }
        }
    }

    private fun revisions(configSet: ConfigSetId) =
        mvc.get("/api/v1/configsets/${configSet.toApiId()}/revisions") { bearer(SUPER_ADMIN) }

    /** One statement sets the last attempt and the due time, so the difference is exact. */
    private fun secondsToDue(configSet: ConfigSetId): Long {
        val state = git.state(configSet)
        return Duration.between(state.lastAttemptAt, state.nextDueAt).seconds
    }

    private companion object {
        /** The default of `folio.sync.interval`. */
        const val INTERVAL_SECONDS = 60L
    }
}
