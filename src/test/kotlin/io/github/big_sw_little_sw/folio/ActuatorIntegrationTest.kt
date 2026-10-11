package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import kotlin.test.*

@FolioServerTest
class ActuatorIntegrationTest(
    @Value("\${local.server.port}") private val serverPort: Int,
    @Value("\${local.management.port}") private val managementPort: Int,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val tracer: Tracer,
) {
    @Test
    fun `the management port serves health and prometheus without a token`() {
        val health = httpGet(managementPort, "/actuator/health")
        assertEquals(200, health.statusCode())
        assertContains(health.body(), "\"UP\"")

        val prometheus = httpGet(managementPort, "/actuator/prometheus")
        assertEquals(200, prometheus.statusCode())
        assertContains(prometheus.body(), "jvm_threads_live_threads")
    }

    /**
     * An anonymous request for the master-key status makes one decision: MANAGE at the root, where no rule exists. The
     * timer and the gauges of the `git` module exist before the first sync and the first sweep.
     */
    @Test
    fun `the Prometheus scrape has the meters of Folio`() {
        assertEquals(401, httpGet(serverPort, "/api/v1/admin/crypto").statusCode())

        val scrape = httpGet(managementPort, "/actuator/prometheus").body().lines()

        val labels = listOf("action=\"MANAGE\"", "reason=\"no_rule\"", "result=\"denied\"")
        assertEquals(
            1,
            scrape.count { line ->
                line.startsWith("folio_authorization_decisions_total{") && labels.all { it in line }
            },
        )
        listOf("folio_sync_fetch_duration_seconds_count", "folio_git_cache_size_bytes", "folio_git_cache_repositories")
            .forEach { name -> assertEquals(1, scrape.count { it.startsWith("$name ") }, name) }
    }

    /**
     * The meters of Spring and of the libraries must also have no IDs and no SQL text as tags (design 15.1). The
     * request with an API ID shows that the `uri` tag has the route pattern, not the ID.
     */
    @Test
    fun `no series of the Prometheus scrape has an ID or SQL text`() {
        assertEquals(404, httpGet(serverPort, "/api/v1/configsets/cfg_${"0".repeat(32)}").statusCode())

        val samples = httpGet(managementPort, "/actuator/prometheus").body().lines().filterNot { it.startsWith("#") }

        assertEquals(emptyList(), samples.filter { Regex("_[0-9a-f]{32}").containsMatchIn(it) || "select " in it })
    }

    @Test
    fun `OTLP metrics and traces are off by default`() {
        val registries = ((meters as? CompositeMeterRegistry)?.registries ?: setOf(meters)).map { it.javaClass.simpleName }
        val span = tracer.nextSpan()
        span.end()

        assertContains(registries, "PrometheusMeterRegistry")
        assertEquals(emptyList(), registries.filter { "Otlp" in it })
        assertNotEquals(true, span.context().sampled())
    }

    @Test
    fun `no other actuator endpoint is exposed`() {
        listOf("env", "configprops", "metrics", "beans", "loggers", "heapdump", "sessions").forEach { endpoint ->
            assertEquals(404, httpGet(managementPort, "/actuator/$endpoint").statusCode(), endpoint)
        }
    }

    @Test
    fun `the application port serves no actuator endpoint`() {
        assertEquals(404, httpGet(serverPort, "/actuator/health").statusCode())
        assertEquals(404, httpGet(serverPort, "/actuator/prometheus").statusCode())
    }

    @Test
    fun `the production configuration puts the actuator on port 8081`() {
        val production = YamlPropertySourceLoader().load("production", ClassPathResource("application.yaml"))

        assertEquals(8081, production.single().getProperty("management.server.port"))
    }
}
