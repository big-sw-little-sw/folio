package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.FolioServerTest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The actuator on its own port (design 15.2): health and prometheus without a token, nothing else. */
@FolioServerTest
class ActuatorIntegrationTest(
    @Value("\${local.server.port}") private val serverPort: Int,
    @Value("\${local.management.port}") private val managementPort: Int,
) {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `the management port serves health and prometheus without a token`() {
        val health = get(managementPort, "/actuator/health")
        assertEquals(200, health.statusCode())
        assertContains(health.body(), "\"UP\"")

        val prometheus = get(managementPort, "/actuator/prometheus")
        assertEquals(200, prometheus.statusCode())
        assertContains(prometheus.body(), "jvm_threads_live_threads")
    }

    @Test
    fun `no other actuator endpoint is exposed`() {
        listOf("env", "configprops", "metrics", "beans", "loggers", "heapdump", "sessions").forEach { endpoint ->
            assertEquals(404, get(managementPort, "/actuator/$endpoint").statusCode(), endpoint)
        }
    }

    @Test
    fun `the application port serves no actuator endpoint`() {
        assertEquals(404, get(serverPort, "/actuator/health").statusCode())
        assertEquals(404, get(serverPort, "/actuator/prometheus").statusCode())
    }

    @Test
    fun `the production configuration puts the actuator on port 8081`() {
        val production = YamlPropertySourceLoader().load("production", ClassPathResource("application.yaml"))

        assertEquals(8081, production.single().getProperty("management.server.port"))
    }

    private fun get(port: Int, path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
