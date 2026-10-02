package io.github.big_sw_little_sw.folio.consumption

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SourceCache
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.sync.SyncFixture
import io.github.big_sw_little_sw.folio.sync.internal.Synchronizer
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectInserter
import org.hamcrest.Matchers.contains
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bounds on fetching for reads (ADR 0036) and on file size (ADR 0035), counting the Git commands the server runs.
 * Every fetch runs two: its ls-remote and the fetch itself.
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
// A fetch slot for each concurrent reader, so that none of them is turned away before the lock.
@SpringBootTest(properties = ["folio.consumption.max-file-size=4B", "folio.consumption.max-concurrent-fetches=6"])
@AutoConfigureMockMvc
class ConsumptionFetchIntegrationTest(
    @Autowired mvc: MockMvc,
    @Autowired synchronizer: Synchronizer,
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
        // Each command waits a second, so that concurrent reads overlap the one fetch.
        fixture.reset(SshGitServer.logged(LOG, seconds = 1))
        fixture.sync()
        cache.delete(fixture.configSet.id.value)
        SshGitServer.clearLog(LOG)
    }

    @Test
    fun `concurrent reads of a revision the cache lacks fetch it once`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(READERS)
        val statuses =
            try {
                val reads =
                    List(READERS) {
                        pool.submit(
                            Callable {
                                start.await()
                                fixture
                                    .get("${fixture.url}/files/app.yaml")
                                    .andReturn()
                                    .response.status
                            },
                        )
                    }
                start.countDown()
                reads.map { it.get() }
            } finally {
                pool.shutdown()
            }

        // Every reader has a fetch slot; all but one wait for the one fetch and find the commit after it.
        assertTrue(statuses.count { it == 200 } >= 2, "$statuses")
        assertEquals(2, SshGitServer.loggedCommands(LOG), "one fetch is its ls-remote and the fetch itself")
    }

    @Test
    fun `a revision still missing after a fetch is not fetched for again for a while`() {
        val lost = "f".repeat(40)
        jdbc
            .sql("insert into synced_revision (config_set_id, commit_id, synced_at) values (:id, :commit, now())")
            .param("id", fixture.configSet.id.value)
            .param("commit", lost)
            .update()

        repeat(3) {
            fixture.get("${fixture.url}/files/app.yaml?revision=$lost").andExpect { status { isNotFound() } }
        }

        assertEquals(2, SshGitServer.loggedCommands(LOG))
    }

    @Test
    fun `nothing is fetched while the last sync found the repository too large`() {
        jdbc
            .sql("update sync_state set last_error_code = :code, last_error_summary = :summary")
            .param("code", SourceFailure.REPOSITORY_TOO_LARGE.name)
            .param("summary", SourceFailure.REPOSITORY_TOO_LARGE.summary)
            .update()

        fixture.get("${fixture.url}/files/app.yaml").andExpect {
            status { isBadGateway() }
            jsonPath("$.code") { value("REPOSITORY_TOO_LARGE") }
        }
        assertEquals(0, SshGitServer.loggedCommands(LOG))
    }

    @Test
    fun `a file over the maximum file size is refused with 422, while its listing and a 304 still work`() {
        SshGitServer.addCommit(REPOSITORY, "too large", "config/big.txt")
        fixture.sync()

        fixture.get("${fixture.url}/files").andExpect {
            jsonPath("$.files[?(@.path == 'big.txt')].size") { value(contains(9)) }
        }
        fixture.get("${fixture.url}/files/big.txt").andExpect {
            status { isEqualTo(422) }
            jsonPath("$.detail") { value("File 'big.txt' is larger than 4 bytes") }
        }
        // The entity tag is compared before the file would be loaded.
        val blobId = ObjectInserter.Formatter().idFor(Constants.OBJ_BLOB, "too large".toByteArray()).name
        fixture.get("${fixture.url}/files/big.txt", ifNoneMatch = "\"$blobId\"").andExpect {
            status { isNotModified() }
        }
        fixture.get("${fixture.url}/files/app.yaml").andExpect { status { isOk() } }
    }

    companion object {
        private const val REPOSITORY = "consumption-fetch"
        private const val LOG = "/tmp/consumption-fetch.log"
        private const val READERS = 6

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
