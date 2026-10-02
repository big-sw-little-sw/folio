package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

/** Actuator exposure (ADR 0040): health and Prometheus without a token, nothing else at all. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
// Tests export no metrics by default; this one checks the Prometheus endpoint.
@AutoConfigureMetrics
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorIntegrationTest(
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun `the Prometheus endpoint needs no token and serves Folio's metrics`() {
        mvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content { string(containsString("folio_sync_fetch_duration_seconds")) }
        }
    }

    @Test
    fun `health needs no token`() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `no other actuator endpoint is exposed, even to a super admin`() {
        listOf("env", "configprops", "metrics", "beans", "loggers", "heapdump").forEach { endpoint ->
            mvc.get("/actuator/$endpoint") { with(jwt().jwt { it.subject(SUPER_ADMIN) }) }.andExpect {
                status { isNotFound() }
            }
        }
    }
}
