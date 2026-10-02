package io.github.big_sw_little_sw.folio.consumption

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceCache
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.sync.SyncFixture
import io.github.big_sw_little_sw.folio.sync.internal.Synchronizer
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import kotlin.test.Test

/**
 * Which revisions the consumption API serves: `latest` is the synced revision, exact reads only synced revisions, and a
 * cache that lacks one fetches it on demand (ADR 0032, ADR 0036).
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsumptionRevisionIntegrationTest(
    @Autowired mvc: MockMvc,
    @Autowired synchronizer: Synchronizer,
    @Autowired private val sources: SourceAccess,
    @Autowired private val cache: SourceCache,
    @Autowired configSets: ConfigSetService,
    @Autowired credentials: CredentialService,
    @Autowired namespaces: NamespaceService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val fixture =
        ConsumptionFixture(mvc, synchronizer, SyncFixture(configSets, credentials, namespaces, jdbc, REPOSITORY))

    @BeforeEach
    fun setUp() {
        fixture.reset()
    }

    @Test
    fun `latest follows the synced revision, not the branch tip in the cache`() {
        fixture.sync()
        val third = SshGitServer.addCommit(REPOSITORY, "v3")
        // An onboarding check would fetch like this: the cache's branch tip moves, the synced revision does not.
        sources.fetch(fixture.configSet.id.value, fixture.configSet.source)

        fixture.get("${fixture.url}/files/app.yaml").andExpect {
            content { string("v2") }
            header { string(REVISION, fixture.commits.last()) }
        }

        fixture.sync()
        fixture.get("${fixture.url}/files/app.yaml").andExpect {
            content { string("v3") }
            header { string(REVISION, third) }
        }
    }

    @Test
    fun `a ConfigSet that never synced has no latest and no revisions`() {
        listOf("/files", "/files/app.yaml").forEach {
            fixture.get(fixture.url + it).andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value("The ConfigSet has not synced yet") }
            }
        }
        fixture.get("${fixture.url}/revisions").andExpect {
            status { isOk() }
            jsonPath("$.revisions") { value(empty<Any>()) }
        }
    }

    @Test
    fun `a commit Folio never synced is not served, even when the cache holds it`() {
        fixture.sync()
        // The first commit came with the fetch of the second, but was never the synced revision.
        listOf(fixture.commits.first(), "0".repeat(40)).forEach { commit ->
            listOf("/files", "/files/app.yaml").forEach {
                fixture.get("${fixture.url}$it?revision=$commit").andExpect {
                    status { isNotFound() }
                    jsonPath("$.detail") { value("Revision '$commit' not found") }
                }
            }
        }
        listOf("main", "HEAD", "refs/heads/main", fixture.commits.last().uppercase()).forEach {
            fixture.get("${fixture.url}/files?revision=$it").andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `a cache without the synced revision fetches it on demand`() {
        fixture.sync()
        val synced = fixture.commits.last()
        cache.delete(fixture.configSet.id.value)

        fixture.get("${fixture.url}/files/app.yaml").andExpect {
            status { isOk() }
            content { string("v2") }
        }
        cache.delete(fixture.configSet.id.value)
        fixture.get("${fixture.url}/files?revision=$synced").andExpect {
            status { isOk() }
            jsonPath("$.revision") { value(synced) }
        }
    }

    @Test
    fun `a failed on-demand fetch gives 502 with the failure code and its fixed summary only`() {
        fixture.sync()
        cache.delete(fixture.configSet.id.value)
        SshGitServer.revokeAll()

        fixture.get("${fixture.url}/files/app.yaml").andExpect {
            status { isBadGateway() }
            jsonPath("$.code") { value("AUTH_FAILED") }
            jsonPath("$.detail") { value(SourceFailure.AUTH_FAILED.summary) }
            content { string(not(containsString("Exception"))) }
            content { string(not(containsString("repos/"))) }
            content { string(not(containsString("ssh"))) }
        }
    }

    @Test
    fun `a synced revision that fetching cannot bring back gives 404`() {
        fixture.sync()
        val lost = "f".repeat(40)
        jdbc
            .sql("insert into synced_revision (config_set_id, commit_id, synced_at) values (:id, :commit, now())")
            .param("id", fixture.configSet.id.value)
            .param("commit", lost)
            .update()

        fixture.get("${fixture.url}/files/app.yaml?revision=$lost").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Revision '$lost' is no longer available") }
        }
    }

    @Test
    fun `the revision listing has each synced revision once, newest first`() {
        val (_, second) = fixture.commits
        fixture.sync()
        fixture.sync()
        val third = SshGitServer.addCommit(REPOSITORY, "v3")
        fixture.sync()

        expectRevisions(third, second)

        // A branch reset to an earlier commit makes it latest again, so it moves to the top.
        SshGitServer.resetMain(REPOSITORY, second)
        fixture.sync()

        expectRevisions(second, third)
        fixture.get("${fixture.url}/files/app.yaml").andExpect { content { string("v2") } }
    }

    private fun expectRevisions(vararg commits: String) {
        fixture.get("${fixture.url}/revisions").andExpect {
            status { isOk() }
            jsonPath("$.revisions[*].id") { value(contains(*commits)) }
            jsonPath("$.revisions[0].syncedAt") { exists() }
            header { string("Cache-Control", "private, max-age=30") }
        }
    }

    companion object {
        private const val REPOSITORY = "consumption-revisions"
        private const val REVISION = "X-Config-Revision"

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
