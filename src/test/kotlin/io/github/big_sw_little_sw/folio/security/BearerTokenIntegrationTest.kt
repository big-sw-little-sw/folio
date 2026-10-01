package io.github.big_sw_little_sw.folio.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import kotlin.test.Test

/**
 * Real signed tokens through the production decoder, which Boot builds from the issuer's metadata. A JDK
 * HTTP server plays the issuer, so no real IdP is needed. Other tests use `jwt()`, which skips decoding.
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BearerTokenIntegrationTest(
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun `accepts a token for Folio's audience`() {
        mvc.get(NAMESPACES) { bearer(token(AUDIENCE)) }.andExpect { status { isOk() } }
    }

    @Test
    fun `rejects a token the issuer signed for another audience`() {
        mvc.get(NAMESPACES) { bearer(token("another-api")) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `rejects a token whose claims do not map to a principal`() {
        mvc.get(NAMESPACES) { bearer(token(AUDIENCE, subject = null)) }.andExpect { status { isUnauthorized() } }
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun token(
        audience: String,
        subject: String? = "alice",
    ): String {
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuer())
                .audience(listOf(audience))
                .issuedAt(now)
                .expiresAt(now + Duration.ofMinutes(5))
        subject?.let { claims.subject(it) }
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.keyID).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).tokenValue
    }

    companion object {
        private const val NAMESPACES = "/api/v1/admin/namespaces"

        /** Matches `audiences` in src/test/resources/config/application.yaml. */
        private const val AUDIENCE = "folio-test"
        private const val KEY_SIZE = 2048

        private val KEY: RSAKey = RSAKeyGenerator(KEY_SIZE).keyID("test-key").generate()
        private val encoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(KEY)))
        private val issuerServer: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)

        private fun issuer() = "http://localhost:${issuerServer.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun issuerProperties(registry: DynamicPropertyRegistry) {
            issuerServer.createContext("/.well-known/openid-configuration") {
                respond(it, """{"issuer": "${issuer()}", "jwks_uri": "${issuer()}/jwks"}""")
            }
            issuerServer.createContext("/jwks") { respond(it, JWKSet(KEY.toPublicJWK()).toString()) }
            issuerServer.start()
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ::issuer)
        }

        @JvmStatic
        @AfterAll
        fun stopIssuer() {
            issuerServer.stop(0)
        }

        private fun respond(
            exchange: HttpExchange,
            json: String,
        ) {
            val body = json.toByteArray()
            exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }
}
