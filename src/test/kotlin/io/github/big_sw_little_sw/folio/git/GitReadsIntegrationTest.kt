package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.content.ConfigSource
import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.content.RevisionId
import io.github.big_sw_little_sw.folio.git.internal.GitSourceRepository
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.InMemorySource.TestItem
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.*
import kotlin.test.*

/**
 * These tests read published revisions of Git ConfigSets through the consumption API and through the Git adapter.
 * [TestGit.publishTip] publishes the revisions through sync. The context limits files to 1 KB
 * (`folio.content.max-file-size`).
 */
@GitIntegrationTest
@FreshGitRepository
class GitReadsIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val source: ConfigSource,
    @Autowired private val gitSourceRepository: GitSourceRepository,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `before the first publish, latest is not published yet`() {
        val configSet = git.connected()
        get(configSet, "").andExpect {
            status { isOk() }
            jsonPath("$.latestRevision") { value(null) }
        }
        get(configSet, "/files").andExpectProblem(404, "NOT_PUBLISHED_YET")
    }

    @Test
    fun `the files of the latest revision are the regular files below the root path, with item paths only`() {
        val configSet = git.connected()
        val tip = git.publishTip(configSet)

        get(configSet, "").andExpect { jsonPath("$.latestRevision") { value(tip) } }
        get(configSet, "/revisions").andExpect { jsonPath("$.revisions[*].id") { value(contains(tip)) } }
        // The symbolic link, README.md outside the root path and the file with a backslash are not in the list.
        get(configSet, "/files").andExpect {
            status { isOk() }
            header { string("X-Folio-Revision", tip) }
            jsonPath("$.files[*].path") { value(contains("app.yaml", "run.sh", "sub/extra.json")) }
            jsonPath("$.files[*].size") { value(contains(2, 1, 2)) }
        }
    }

    @Test
    fun `a file read gives the content, and its ETag is the Git blob ID`() {
        val configSet = git.connected()
        val tip = git.publishTip(configSet)

        get(configSet, "/files/app.yaml?revision=$tip").andExpect {
            status { isOk() }
            content { string("v2") }
            header { string(HttpHeaders.ETAG, "\"${TestItem.blobId("v2".toByteArray())}\"") }
            header { string("X-Folio-Validation-Status", "VALID") }
        }
        get(configSet, "/files/sub/extra.json").andExpect { content { string("{}") } }
        // An executable file is a regular file, so it is a ConfigItem.
        get(configSet, "/files/run.sh").andExpect { content { string("x") } }
    }

    @Test
    fun `the Git adapter refuses a file above the size limit before it calls the consumer`() {
        val configSet = git.connected()
        val revision = RevisionId(git.publishTip(configSet))
        val path = ItemPath("app.yaml")

        assertFailsWith<ContentException.ItemTooLarge> {
            source.read(configSet, revision, path, maxBytes = 1) { _, _ -> fail("The consumer ran.") }
        }
        val content = source.read(configSet, revision, path, maxBytes = 2) { _, stream -> stream.readAllBytes() }
        assertEquals("v2", content.decodeToString())
    }

    @Test
    fun `a file above folio-content-max-file-size is refused, and the file list still shows it`() {
        val configSet = git.connected()
        SshGitServer.addCommit("configs", "x".repeat(LARGE_FILE_BYTES))
        git.publishTip(configSet)

        get(configSet, "/files/app.yaml").andExpectProblem(422, "ITEM_TOO_LARGE")
        get(configSet, "/files").andExpect {
            jsonPath("$.files[0].path") { value("app.yaml") }
            jsonPath("$.files[0].size") { value(LARGE_FILE_BYTES) }
        }
    }

    /** JGit streams a blob above 3 MB from the pack. The response must still have the exact blob bytes. */
    @Test
    fun `a file above 3 MB streams with the bytes of its blob`() {
        val commit = SshGitServer.createLargeRepository("large", STREAMED_FILE_BYTES)
        val configSet = git.configSet(git.authorizedCredential().id, repository = "large")
        git.publishTip(configSet)

        val (etag, bytes) =
            source.read(configSet, RevisionId(commit), ItemPath("big.bin"), Long.MAX_VALUE) { item, stream ->
                item.etag to stream.readAllBytes()
            }

        assertEquals(STREAMED_FILE_BYTES, bytes.size)
        assertEquals(etag, TestItem.blobId(bytes))
    }

    /**
     * Trees that share subtrees name 100,000 files in a small pack. The list stops at its limit, so a small repository
     * cannot make Folio build a large list in memory. The publish step of sync refuses such a revision, but the list
     * keeps its own limit. Thus this test publishes the commit directly with [GitSourceRepository.publish].
     */
    @Test
    fun `a revision with too many files below the root path fails the file list with TOO_MANY_FILES`() {
        val commit = SshGitServer.createSharedTreeRepository("shared")
        val configSet = git.configSet(git.authorizedCredential().id, repository = "shared")
        gitSourceRepository.publish(configSet, git.fetchTip(configSet))

        val failure = assertFailsWith<ContentException.SourceFailed> { source.list(configSet, RevisionId(commit)) }

        assertEquals("TOO_MANY_FILES", failure.code)
        get(configSet, "/files").andExpectProblem(502, "TOO_MANY_FILES")
    }

    @Test
    fun `an earlier published revision stays readable after a new publish`() {
        val configSet = git.connected()
        val first = git.publishTip(configSet)
        val second = SshGitServer.addCommit("configs", "v3")
        git.publishTip(configSet)

        get(configSet, "/files/app.yaml").andExpect { content { string("v3") } }
        get(configSet, "/files/app.yaml?revision=$first").andExpect { content { string("v2") } }
        get(configSet, "/revisions").andExpect { jsonPath("$.revisions[*].id") { value(contains(second, first)) } }
    }

    @Test
    fun `the Git source route gives the last published commit`() {
        val configSet = git.connected()
        val tip = git.publishTip(configSet)

        mvc.get("$ADMIN/${configSet.toApiId()}/git") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.lastPublishedCommit") { value(tip) }
            jsonPath("$.lastPublishedAt") { exists() }
        }
    }

    @Test
    fun `a commit that sync did not publish is not readable, also when the cache has it`(commits: Commits) {
        val configSet = git.connected()
        git.publishTip(configSet)

        get(configSet, "/files?revision=${commits.first}").andExpectProblem(404, "REVISION_NOT_FOUND")
        get(configSet, "/files/app.yaml?revision=${commits.first}").andExpectProblem(404, "REVISION_NOT_FOUND")
    }

    @Test
    fun `paths that are not regular files below the root path are not found`() {
        val configSet = git.connected()
        git.publishTip(configSet)

        listOf("README.md", "link", "sub", "missing.yaml").forEach {
            get(configSet, "/files/$it").andExpectProblem(404, "ITEM_NOT_FOUND")
        }
    }

    /** Each instance has its own cache, so a read fetches a published commit that the cache lacks. */
    @Test
    fun `a read of a published revision that the cache of this instance does not have fetches it`() {
        val configSet = git.connected()
        val tip = git.publishTip(configSet)

        git.dropCache(configSet)

        get(configSet, "/files?revision=$tip").andExpect { jsonPath("$.files[0].path") { value("app.yaml") } }
        get(configSet, "/files/app.yaml").andExpect { content { string("v2") } }
    }

    /** The fetch forces the ref of the branch, so the cache also follows a force push back to an earlier commit. */
    @Test
    fun `a commit that the branch returns to becomes latest again`() {
        val configSet = git.connected()
        val second = git.publishTip(configSet)
        val third = SshGitServer.addCommit("configs", "v3")
        git.publishTip(configSet)

        SshGitServer.resetBranch("configs", second)

        assertEquals(second, git.publishTip(configSet))
        get(configSet, "").andExpect { jsonPath("$.latestRevision") { value(second) } }
        get(configSet, "/revisions").andExpect { jsonPath("$.revisions[*].id") { value(contains(second, third)) } }
    }

    /** A delete of a ConfigSet also deletes its Source definition and its revision history. */
    @Test
    fun `a delete of a ConfigSet with published revisions deletes its revisions, and consumers get 404`() {
        val configSet = git.connected()
        val tip = git.publishTip(configSet)

        mvc.delete("$ADMIN/${configSet.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect { status { isNoContent() } }

        get(configSet, "/files?revision=$tip").andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
        val revisions =
            jdbc
                .sql("select count(*) from git_revision where config_set_id = :id")
                .param("id", configSet.value)
                .query(Int::class.java)
                .single()
        assertEquals(0, revisions)
    }

    private fun get(configSet: ConfigSetId, route: String) =
        mvc.get("/api/v1/configsets/${configSet.toApiId()}$route") { bearer(SUPER_ADMIN) }

    private companion object {
        const val ADMIN = "/api/v1/admin/configsets"

        /** Larger than `folio.content.max-file-size` of the Git test context, which is 1 KB. */
        const val LARGE_FILE_BYTES = 2000

        /** Larger than the 3 MB above which JGit streams a blob. */
        const val STREAMED_FILE_BYTES = 4_000_000
    }
}
