package io.github.big_sw_little_sw.folio.identity

import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestIssuer
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.test.Test

/** Bearer tokens on /api (design 6.1), validated by the production decoder against [TestIssuer]. */
@FolioIntegrationTest
class BearerTokenIntegrationTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `a request without a token is anonymous`() {
        mvc.get(CALLER).andExpect {
            status { isOk() }
            jsonPath("$.type") { value("ANONYMOUS") }
        }
    }

    @Test
    fun `a valid token gives the caller from its claims`() {
        val token = TestIssuer.token(mapOf("sub" to "alice", "groups" to listOf("team-b", "team-a"), "azp" to "cli"))

        mvc.get(CALLER) { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.type") { value("AUTHENTICATED") }
            jsonPath("$.subject") { value("alice") }
            jsonPath("$.groups[0]") { value("team-a") }
            jsonPath("$.groups[1]") { value("team-b") }
            jsonPath("$.applicationId") { value("cli") }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    fun `a token that is not valid is 401, never anonymous`(token: String) {
        mvc.get(CALLER) { bearer(token) }.andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("INVALID_TOKEN") }
            jsonPath("$.detail") { value("The bearer token is not valid.") }
            jsonPath("$.type") { doesNotExist() }
        }
    }

    @Test
    fun `the API sets no session cookie and needs no CSRF token`() {
        mvc
            .post("/api/test/echo") {
                bearer(TestIssuer.token())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text": "hello"}"""
            }.andExpect {
                status { isOk() }
                header { doesNotExist(HttpHeaders.SET_COOKIE) }
            }
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private companion object {
        const val CALLER = "/api/test/caller"

        @JvmStatic
        fun invalidTokens(): List<Named<String>> = listOf(
            named("bad signature", TestIssuer.token(signingKey = TestIssuer.foreignKey)),
            named("another audience", TestIssuer.token(audience = "another-api")),
            named("no audience", TestIssuer.token(audience = null)),
            named("expired", TestIssuer.token(expiresAt = Instant.now() - Duration.ofMinutes(5))),
            named("another issuer", TestIssuer.token(issuer = "https://another-issuer.test")),
            named("no subject", TestIssuer.token(claims = mapOf("groups" to listOf("team-a")))),
            named("groups not a list", TestIssuer.token(claims = mapOf("sub" to "alice", "groups" to "team-a"))),
            named("unsigned (alg none)", unsigned()),
            named("not a JWT", "not-a-jwt"),
        )

        /** A token with valid claims and no signature. */
        fun unsigned(): String = PlainJWT(
            JWTClaimsSet
                .Builder()
                .issuer(TestIssuer.uri)
                .audience(TestIssuer.AUDIENCE)
                .subject("alice")
                .expirationTime(Date.from(Instant.now() + Duration.ofMinutes(5)))
                .build(),
        ).serialize()
    }
}
