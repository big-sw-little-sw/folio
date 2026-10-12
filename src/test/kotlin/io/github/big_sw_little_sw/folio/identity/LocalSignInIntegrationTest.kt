package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.MockOidcProvider
import io.github.big_sw_little_sw.folio.MockOidcProviderConfiguration
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.OidcBrowser.Companion.SIGN_IN_LINK
import io.github.big_sw_little_sw.testing.OidcBrowser.Companion.STRING
import io.github.big_sw_little_sw.testing.OidcBrowser.Companion.form
import io.github.big_sw_little_sw.testing.OidcBrowser.Companion.formRequest
import io.github.big_sw_little_sw.testing.OidcBrowser.Companion.location
import io.github.big_sw_little_sw.testing.TestRoutes.Companion.CALLER
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.session.FindByIndexNameSessionRepository
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import kotlin.test.*

/**
 * The local setup of `bootTestRun`: Folio with the mock OIDC provider. A person signs in through the real authorization
 * code flow, and a workload calls the API with a client credentials token.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, MockOidcProviderConfiguration::class, TestRoutes::class, TestCatalog::class)
class LocalSignInIntegrationTest(
    @Value("\${local.server.port}") private val port: Int,
    @Autowired private val provider: MockOidcProvider,
    @Autowired private val json: JsonMapper,
    @Autowired private val sessions: FindByIndexNameSessionRepository<*>,
    @Autowired private val catalog: TestCatalog,
) {
    private val folio = "http://localhost:$port"
    private val browser = OidcBrowser(folio, provider.issuer())

    @Test
    fun `a person signs in to the UI and the session does not authenticate the API`() {
        val page = signIn("alice")

        assertContains(page, "Signed in as <a href=\"/me\"><strong>alice</strong></a>")
        assertContains(browser.get("$folio/me").body(), "<code>team-a</code>")
        val lastAccess = lastAccess("alice")
        Thread.sleep(Duration.ofMillis(SESSION_CLOCK_STEP_MILLIS))
        // The browser also sends the session cookie to the API. The API must ignore the session, so the caller is
        // anonymous and the session keeps its last access time.
        assertContains(browser.get("$folio$CALLER").body(), "ANONYMOUS")
        assertEquals(lastAccess, lastAccess("alice"))
    }

    @Test
    fun `an anonymous browser goes to sign-in and then back to the page`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.READ, "user:carol")
        val page = "/namespaces/${top.toApiId()}"

        assertEquals("/sign-in", URI.create(location(browser.get("$folio$page"))).path)
        val authorization = URI.create(folio).resolve(location(browser.get("$folio/sign-in"))).toString()
        val back = URI.create(folio).resolve(location(browser.get(browser.callback(authorization, "carol", TEAM_A))))

        assertEquals(page, back.path)
        val response = browser.get(back.toString())
        assertEquals(200, response.statusCode())
        assertContains(response.body(), "<code>${top.toApiId()}</code>")
    }

    @Test
    fun `sign-out ends the session`() {
        val page = signIn("bob")
        val session =
            browser.cookies.cookieStore.cookies
                .single { it.name == "SESSION" }
                .value
        val csrf = requireNotNull(CSRF_FIELD.find(page)).groupValues[1]

        val signOut = browser.post("$folio/sign-out", mapOf("_csrf" to csrf))

        assertEquals("$folio/", URI.create(folio).resolve(location(signOut)).toString())
        assertContains(browser.get("$folio/").body(), SIGN_IN_LINK)
        // Sign-out ends the session on the server, so a client that kept the old cookie is also not signed in.
        val replay = HttpRequest.newBuilder(URI.create("$folio/")).header("Cookie", "SESSION=$session").build()
        assertContains(HttpClient.newHttpClient().send(replay, STRING).body(), SIGN_IN_LINK)
    }

    @Test
    fun `a workload calls the API with a client credentials token`() {
        val form =
            form(
                mapOf(
                    "grant_type" to "client_credentials",
                    "client_id" to "my-app",
                    "client_secret" to "x",
                    "scope" to "folio",
                ),
            )
        val tokenResponse = HttpClient.newHttpClient().send(formRequest("${provider.issuer()}/token", form), STRING)
        val token = json.readTree(tokenResponse.body()).get("access_token").asString()

        val request = HttpRequest.newBuilder(URI.create("$folio$CALLER")).header("Authorization", "Bearer $token")
        val caller = HttpClient.newHttpClient().send(request.build(), STRING)

        assertEquals(200, caller.statusCode())
        assertContains(caller.body(), "\"subject\":\"my-app\"")
    }

    private fun lastAccess(username: String) = sessions.findByPrincipalName(username).values.single().lastAccessedTime

    /** Signs in through the mock provider with the group `team-a`, and returns the start page. */
    private fun signIn(username: String): String = browser.get(location(browser.signIn(username, TEAM_A))).body()

    private companion object {
        const val TEAM_A = """{"groups": ["team-a"]}"""
        val CSRF_FIELD = Regex("<input type=\"hidden\" name=\"_csrf\" value=\"([^\"]+)\">")

        /**
         * Spring Session stores the time of the last access with millisecond precision. The test waits more than one
         * millisecond, so an access through the API would change the stored time.
         */
        const val SESSION_CLOCK_STEP_MILLIS = 20L
    }
}
