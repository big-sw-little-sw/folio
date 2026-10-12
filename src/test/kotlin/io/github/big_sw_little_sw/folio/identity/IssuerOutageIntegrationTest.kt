package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.testing.*
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.*
import kotlin.test.*

/** This test has its own application context, because the context reads the issuer at its start. */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestRoutes::class, KeylessIssuerConfiguration::class)
class IssuerOutageIntegrationTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `a token is ISSUER_UNAVAILABLE when the issuer gives no signing keys`() {
        mvc
            .get("/api/test/caller") { bearer(TestIssuer.token(issuer = KeylessIssuer.uri)) }
            .andExpectProblem(503, "ISSUER_UNAVAILABLE")
    }

    @Test
    fun `an anonymous request still works`() {
        mvc.get("/api/test/caller").andExpect {
            status { isOk() }
            jsonPath("$.type") { value("ANONYMOUS") }
        }
    }
}
