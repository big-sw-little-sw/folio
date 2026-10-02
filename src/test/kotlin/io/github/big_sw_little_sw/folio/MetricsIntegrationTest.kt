package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.toApiId
import io.github.big_sw_little_sw.folio.consumption.ConsumptionFixture
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SourceCache
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.sync.SyncFixture
import io.github.big_sw_little_sw.folio.sync.internal.Synchronizer
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The metrics of ADR 0039, read from the `MeterRegistry`. Test classes share the registry of a cached context, so each
 * check compares counts before and after.
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MetricsIntegrationTest(
    @Autowired mvc: MockMvc,
    @Autowired synchronizer: Synchronizer,
    @Autowired private val meters: MeterRegistry,
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
    fun `sync attempts are counted by outcome and failure code, and every fetch is timed`() {
        val synced = Counted("folio.sync.attempts", "outcome", "synced", "code", "none")
        val unchanged = Counted("folio.sync.attempts", "outcome", "unchanged", "code", "none")
        val failed = Counted("folio.sync.attempts", "outcome", "failed", "code", "AUTH_FAILED")
        val fetches = meters.timer("folio.sync.fetch.duration").count()

        fixture.sync()
        fixture.sync()
        SshGitServer.revokeAll()
        fixture.sync()

        assertEquals(listOf(1.0, 1.0, 1.0), listOf(synced.added(), unchanged.added(), failed.added()))
        assertEquals(fetches + 3, meters.timer("folio.sync.fetch.duration").count())
    }

    @Test
    fun `authorization decisions are counted by action, result and reason`() {
        val noRule = decision("denied", "no_rule")
        fixture.get(fixture.url, token = null)
        assertEquals(1.0, noRule.added())

        fixture.grant("CONFIG_SET_VIEW", "user:alice")
        val counted =
            mapOf(
                ConsumptionFixture.ADMIN to decision("allowed", "super_admin"),
                ConsumptionFixture.ALICE to decision("allowed", "rule"),
                jwt().jwt { it.subject("bob") } to decision("denied", "not_granted"),
            )

        counted.keys.forEach { fixture.get(fixture.url, it) }

        counted.values.forEach { assertEquals(1.0, it.added(), it.tags.toString()) }
        assertEquals(setOf("action", "result", "reason"), tagKeys("folio.authorization.decisions"))
    }

    @Test
    fun `content reads are timed by Spring by URI template and status, without IDs or paths`() {
        fixture.sync()
        val latest = fixture.commits.last()
        val reads =
            listOf(
                Read(fixture.url, CONFIG_SET, "200"),
                Read("${fixture.url}/files", "$CONFIG_SET/files", "200"),
                Read("${fixture.url}/files?revision=nope", "$CONFIG_SET/files", "400"),
                Read("${fixture.url}/files/app.yaml?revision=$latest", "$CONFIG_SET/files/{*path}", "200"),
                Read("${fixture.url}/revisions", "$CONFIG_SET/revisions", "200"),
            )
        val etag =
            fixture
                .get("${fixture.url}/files/app.yaml")
                .andReturn()
                .response
                .getHeader(HttpHeaders.ETAG)
        val counts = reads.map { it.counted() }
        val notModified = Read("${fixture.url}/files/app.yaml", "$CONFIG_SET/files/{*path}", "304").counted()

        reads.forEach { fixture.get(it.url) }
        fixture.get("${fixture.url}/files/app.yaml", ifNoneMatch = etag)

        counts.forEach { assertEquals(1.0, it.added(), it.tags.toList().toString()) }
        assertEquals(1.0, notModified.added())
        val uris = meters.find(REQUESTS).meters().map { it.id.getTag("uri") }
        assertTrue(uris.none { fixture.configSet.id.toApiId() in it.orEmpty() || "app.yaml" in it.orEmpty() })
    }

    @Test
    fun `on-demand fetches are counted by result`() {
        fixture.sync()
        val fetched = fetchResult("fetched")
        val missing = fetchResult("missing")
        val tooLarge = fetchResult("too_large")
        val lost = "f".repeat(40)
        jdbc
            .sql("insert into synced_revision (config_set_id, commit_id, synced_at) values (:id, :commit, now())")
            .param("id", fixture.configSet.id.value)
            .param("commit", lost)
            .update()

        cache.delete(fixture.configSet.id.value)
        fixture.get("${fixture.url}/files/app.yaml")
        // Fetched for and still missing, then remembered as missing without a fetch.
        repeat(2) { fixture.get("${fixture.url}/files/app.yaml?revision=$lost") }
        jdbc
            .sql(
                "update sync_state set last_error_code = :code, last_error_summary = 'too large' " +
                    "where config_set_id = :id",
            ).param("code", SourceFailure.REPOSITORY_TOO_LARGE.name)
            .param("id", fixture.configSet.id.value)
            .update()
        cache.delete(fixture.configSet.id.value)
        fixture.get("${fixture.url}/files/app.yaml")

        assertEquals(listOf(1.0, 2.0, 1.0), listOf(fetched.added(), missing.added(), tooLarge.added()))
    }

    private fun decision(
        result: String,
        reason: String,
    ) = Counted("folio.authorization.decisions", "action", "CONFIG_SET_VIEW", "result", result, "reason", reason)

    private fun fetchResult(result: String) = Counted("folio.consumption.fetches", "result", result)

    private fun tagKeys(name: String) =
        meters
            .find(name)
            .meters()
            .flatMap { it.id.tags }
            .map { it.key }
            .toSet()

    /** A counter's count from now on; zero if it does not exist yet. */
    private inner class Counted(
        val name: String,
        vararg val tags: String,
    ) {
        private val before = count()

        fun added() = count() - before

        private fun count() =
            meters
                .find(name)
                .tags(*tags)
                .counter()
                ?.count() ?: 0.0
    }

    private inner class Read(
        val url: String,
        uri: String,
        status: String,
    ) {
        private val tags = arrayOf("method", "GET", "uri", uri, "status", status)

        fun counted() = TimerCount(tags)
    }

    /** How many times Spring's request timer recorded from now on. */
    private inner class TimerCount(
        val tags: Array<String>,
    ) {
        private val before = count()

        fun added() = count() - before

        private fun count() =
            meters
                .find(REQUESTS)
                .tags(*tags)
                .timer()
                ?.count()
                ?.toDouble() ?: 0.0
    }

    companion object {
        private const val REPOSITORY = "metrics"
        private const val REQUESTS = "http.server.requests"
        private const val CONFIG_SET = "/api/v1/configsets/{id}"

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
