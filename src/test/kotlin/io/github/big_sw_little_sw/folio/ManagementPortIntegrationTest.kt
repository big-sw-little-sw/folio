package io.github.big_sw_little_sw.folio

import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Actuator endpoints on a management port of their own, as the production configuration sets by default (ADR 0040), so
 * that the token-free Prometheus endpoint is not on the application port. A random port stands in for 8081.
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
class ManagementPortIntegrationTest(
    @Value("\${local.server.port}") private val serverPort: Int,
    @Value("\${local.management.port}") private val managementPort: Int,
) {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `Prometheus is served on the management port without a token`() {
        assertEquals(200, status(managementPort, "/actuator/prometheus"))
        assertEquals(200, status(managementPort, "/actuator/health"))
    }

    @Test
    fun `the application port serves no actuator endpoint`() {
        assertEquals(404, status(serverPort, "/actuator/prometheus"))
        assertEquals(404, status(serverPort, "/actuator/health"))
    }

    @Test
    fun `the production configuration puts the actuator on port 8081`() {
        // src/main/resources/application.yaml; the test configuration under config/ moves it to the application port.
        val production = YamlPropertySourceLoader().load("production", ClassPathResource("application.yaml"))

        assertEquals(8081, production.single().getProperty("management.server.port"))
    }

    private fun status(
        port: Int,
        path: String,
    ): Int =
        client
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
}
