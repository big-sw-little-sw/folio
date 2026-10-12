package io.github.big_sw_little_sw.folio.identity

import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.github.big_sw_little_sw.testing.*
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.test.*

/** The production decoder validates each token against [TestIssuer]. */
@FolioIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
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
            jsonPath("$.superAdmin") { value(false) }
        }
    }

    @Test
    fun `a token of a member of a super-admin group gives a super admin`() {
        val token = TestIssuer.token(mapOf("sub" to "root", "groups" to listOf("folio-admins")))

        mvc.get(CALLER) { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.subject") { value("root") }
            jsonPath("$.superAdmin") { value(true) }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    fun `a token that is not valid is 401, never anonymous`(token: String) {
        mvc.get(CALLER) { bearer(token) }.andExpectProblem(401, "INVALID_TOKEN").andExpect {
            jsonPath("$.detail") { value("The bearer token is not valid.") }
        }
    }

    @Test
    fun `a token with claims that give no caller is 401 and is logged, because the claim configuration is wrong`(
        output: CapturedOutput,
    ) {
        val token = TestIssuer.token(claims = mapOf("groups" to listOf("team-a")))

        mvc.get(CALLER) { bearer(token) }.andExpectProblem(401, "INVALID_TOKEN")

        assertContains(output.all, REFUSED)
    }

    @Test
    fun `a token with a bad signature is 401 and is not logged, because it is a fault of the client`(
        output: CapturedOutput,
    ) {
        val token = TestIssuer.token(signingKey = TestIssuer.foreignKey)

        mvc.get(CALLER) { bearer(token) }.andExpectProblem(401, "INVALID_TOKEN")

        assertFalse(REFUSED in output.all)
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

    private companion object {
        const val CALLER = "/api/test/caller"
        const val REFUSED = "A bearer token was refused"

        @JvmStatic
        fun invalidTokens(): List<Named<String>> = listOf(
            named("bad signature", TestIssuer.token(signingKey = TestIssuer.foreignKey)),
            named("another audience", TestIssuer.token(audience = "another-api")),
            named("no audience", TestIssuer.token(audience = null)),
            named("expired", TestIssuer.token(expiresAt = Instant.now() - Duration.ofMinutes(5))),
            named("another issuer", TestIssuer.token(issuer = "https://another-issuer.test")),
            named("groups not a list", TestIssuer.token(claims = mapOf("sub" to "alice", "groups" to "team-a"))),
            named("unsigned (alg none)", unsigned()),
            named("not a JWT", "not-a-jwt"),
        )

        /** Returns a token with valid claims and no signature. */
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
