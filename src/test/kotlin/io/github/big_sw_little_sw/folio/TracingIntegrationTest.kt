package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.testing.GitIntegrationTest
import io.github.big_sw_little_sw.testing.SshGitServer
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestGit
import io.github.big_sw_little_sw.testing.TestIssuer
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These tests turn traces on and collect the spans in memory (design 15.1). Each sync fetch has a span with the
 * ConfigSet ID. HTTP requests and database calls have spans too. No span contains a path, a subject, a token or a
 * parameter value of a statement. A span with an error has the exception type, but no exception message.
 */
@GitIntegrationTest
@TestPropertySource(properties = ["management.tracing.sampling.probability=1.0"])
@Import(TracingIntegrationTest.InMemorySpans::class)
class TracingIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val configSets: ConfigSets,
    @Autowired private val exporter: InMemorySpanExporter,
    @Autowired private val tracerProvider: SdkTracerProvider,
) {
    @Test
    fun `a Git fetch has a span with the ConfigSet ID only`() {
        val traced = trace()

        val fetches = traced.spans.filter { it.name == "git fetch" }

        assertEquals(listOf(StatusCode.OK, StatusCode.ERROR), fetches.map { it.status.statusCode })
        fetches.forEach { fetch ->
            assertEquals(
                mapOf("folio.config_set.id" to traced.configSet),
                fetch.attributes.asMap().mapKeys { it.key.key },
            )
        }
    }

    @Test
    fun `a span with an error has the exception type, but no exception message`() {
        val spans = trace().spans

        val failed = spans.filter { it.status.statusCode == StatusCode.ERROR }
        val exceptions = failed.flatMap { it.events }.filter { it.name == "exception" }

        assertTrue(failed.any { it.name == "query" }, names(failed))
        assertTrue(exceptions.isNotEmpty())
        assertEquals(setOf("exception.type"), exceptions.flatMap { it.attributes.asMap().keys }.map { it.key }.toSet())
        assertEquals(setOf(""), failed.map { it.status.description }.toSet())
    }

    @Test
    fun `HTTP requests and database calls have spans`() {
        val spans = trace().spans

        assertTrue(spans.any { it.name == "http get /api/v1/configsets/{id}/files/{*path}" }, names(spans))
        assertTrue(spans.any { it.name == "query" }, names(spans))
    }

    @Test
    fun `no span contains a path, a subject or a token`() {
        val traced = trace()

        val texts =
            traced.spans.flatMap { span ->
                listOf(span.name, span.status.description) +
                    span.attributes.texts() +
                    span.events.flatMap { it.attributes.texts() }
            }

        assertEquals(emptyList(), texts.filter { text -> traced.secrets.any { it in text } })
    }

    /**
     * Syncs a new ConfigSet, resolves its path and reads a file with a bearer token. Then the function does two steps
     * that fail: a create of a Namespace whose slug a sibling already has, and a sync after the key was revoked.
     * Returns the spans of these steps, the API ID of the ConfigSet, and the values that no span may contain: the
     * paths, the slug of the Namespace, the repository path, the subject, the group and the token.
     */
    private fun trace(): Traced {
        SshGitServer.revokeAll()
        SshGitServer.createRepository("configs")
        val slug = TestCatalog.uniqueSlug("secret-slug")
        val configSet = git.configSet(git.authorizedCredential().id, namespace = catalog.namespace(slug = slug))
        val path = checkNotNull(configSets.find(configSet)).path.toString()
        val subject = "subject-${UUID.randomUUID()}"
        val token = TestIssuer.token(mapOf("sub" to subject, "groups" to listOf(GROUP)))
        exporter.reset()

        git.publishTip(configSet)
        mvc.get("/api/v1/configsets:resolve?path=$path") { bearer(token) }.andExpect { status { isOk() } }
        mvc
            .get("/api/v1/configsets/${configSet.toApiId()}/files/sub/extra.json") { bearer(token) }
            .andExpect { status { isOk() } }
        mvc
            .post("/api/v1/admin/namespaces") {
                bearer(token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"slug": "$slug"}"""
            }.andExpectProblem(409, "SLUG_TAKEN")
        SshGitServer.revokeAll()
        git.sync(configSet)

        val secrets = listOf(path, slug, "extra.json", "configs.git", subject, GROUP, token)
        return Traced(spans(), configSet.toApiId(), secrets)
    }

    private class Traced(val spans: List<SpanData>, val configSet: String, val secrets: List<String>)

    private fun spans(): List<SpanData> {
        tracerProvider.forceFlush().join(FLUSH_SECONDS, TimeUnit.SECONDS)
        return exporter.finishedSpanItems
    }

    private fun Attributes.texts() = asMap().values.map(Any::toString)

    private fun names(spans: List<SpanData>) = spans.map { it.name }.distinct().toString()

    /**
     * The context also exports each span to this in-memory exporter. `WebConfiguration` wraps this exporter as it wraps
     * the other exporters, so the test sees the spans that a real exporter gets.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class InMemorySpans {
        @Bean
        fun inMemorySpanExporter(): InMemorySpanExporter = InMemorySpanExporter.create()
    }

    private companion object {
        const val FLUSH_SECONDS = 10L

        /** The super-admin group of the test configuration. */
        const val GROUP = "folio-admins"
    }
}
