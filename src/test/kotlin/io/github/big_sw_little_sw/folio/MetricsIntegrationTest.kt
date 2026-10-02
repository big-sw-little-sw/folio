package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.folio.configset.ConfigSetService
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
    fun `content reads are timed by route, revision kind and status`() {
        fixture.sync()
        val latest = fixture.commits.last()
        val reads =
            listOf(
                Read(fixture.url, "metadata", "latest", "200"),
                Read("${fixture.url}/files", "listing", "latest", "200"),
                Read("${fixture.url}/files?revision=nope", "listing", "exact", "4xx"),
                Read("${fixture.url}/files/app.yaml?revision=$latest", "file", "exact", "200"),
                Read("${fixture.url}/files/missing.yaml", "file", "latest", "4xx"),
                Read("${fixture.url}/revisions", "revisions", "latest", "200"),
            )
        val counts = reads.map { it.counted() }
        val etag =
            fixture
                .get("${fixture.url}/files/app.yaml")
                .andReturn()
                .response
                .getHeader(HttpHeaders.ETAG)
        val notModified = Read("${fixture.url}/files/app.yaml", "file", "latest", "304").counted()

        reads.forEach { fixture.get(it.url) }
        fixture.get("${fixture.url}/files/app.yaml", ifNoneMatch = etag)

        counts.forEach { assertEquals(1.0, it.added(), it.tags.toString()) }
        assertEquals(1.0, notModified.added())
        assertEquals(setOf("route", "revision", "status"), tagKeys("folio.consumption.reads"))
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
        route: String,
        revision: String,
        status: String,
    ) {
        private val tags = arrayOf("route", route, "revision", revision, "status", status)

        fun counted() = TimerCount(tags)
    }

    /** How many times a timer recorded from now on. */
    private inner class TimerCount(
        val tags: Array<String>,
    ) {
        private val before = count()

        fun added() = count() - before

        private fun count() =
            meters
                .find("folio.consumption.reads")
                .tags(*tags)
                .timer()
                ?.count()
                ?.toDouble() ?: 0.0
    }

    companion object {
        private const val REPOSITORY = "metrics"

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
