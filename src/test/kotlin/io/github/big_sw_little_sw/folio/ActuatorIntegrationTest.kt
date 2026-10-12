package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import kotlin.test.*

@FolioServerTest
class ActuatorIntegrationTest(
    @Value("\${local.server.port}") private val serverPort: Int,
    @Value("\${local.management.port}") private val managementPort: Int,
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
