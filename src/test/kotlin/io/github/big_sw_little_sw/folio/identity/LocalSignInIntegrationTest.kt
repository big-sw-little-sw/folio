package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.MockOidcProvider
import io.github.big_sw_little_sw.folio.MockOidcProviderConfiguration
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.testing.*
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.session.FindByIndexNameSessionRepository
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    private val cookies = CookieManager()
    private val browser = HttpClient.newBuilder().cookieHandler(cookies).build()

    @Test
    fun `a person signs in to the UI and the session does not authenticate the API`() {
        val page = signIn("alice")

        assertContains(page, "Signed in as <a href=\"/me\"><strong>alice</strong></a>")
        assertContains(get("$folio/me").body(), "<code>team-a</code>")
        val lastAccess =
            sessions
                .findByPrincipalName("alice")
                .values
                .single()
                .lastAccessedTime
        Thread.sleep(Duration.ofMillis(SESSION_CLOCK_STEP_MILLIS))
        // The browser sends the session cookie also to the API.
        assertContains(get("$folio/api/test/caller").body(), "ANONYMOUS")
        assertEquals(
            lastAccess,
            sessions
                .findByPrincipalName("alice")
                .values
                .single()
                .lastAccessedTime,
        )
    }

    @Test
    fun `an anonymous browser goes to sign-in and then back to the page`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.READ, "user:carol")
        val page = "/namespaces/${top.toApiId()}"

        assertEquals("/sign-in", URI.create(location(get("$folio$page"))).path)
        val authorization = URI.create(folio).resolve(location(get("$folio/sign-in"))).toString()
        val back = URI.create(folio).resolve(location(get(callback(authorization, "carol"))))

        assertEquals(page, back.path)
        val response = get(back.toString())
        assertEquals(200, response.statusCode())
        assertContains(response.body(), "<code>${top.toApiId()}</code>")
    }

    @Test
    fun `sign-out ends the session`() {
        val page = signIn("bob")
        val session =
            cookies.cookieStore.cookies
                .single { it.name == "SESSION" }
                .value
        val csrf = requireNotNull(CSRF_FIELD.find(page)).groupValues[1]

        val signOut = browser.send(post("$folio/sign-out", form(mapOf("_csrf" to csrf))), STRING)

        assertEquals("$folio/", URI.create(folio).resolve(location(signOut)).toString())
        assertContains(get("$folio/").body(), SIGN_IN_LINK)
        // A client that kept the cookie has no session either.
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
        val tokenResponse = HttpClient.newHttpClient().send(post("${provider.issuer()}/token", form), STRING)
        val token = json.readTree(tokenResponse.body()).get("access_token").asString()

        val request =
            HttpRequest
                .newBuilder(
                    URI.create("$folio/api/test/caller"),
                ).header("Authorization", "Bearer $token")
        val caller = HttpClient.newHttpClient().send(request.build(), STRING)

        assertEquals(200, caller.statusCode())
        assertContains(caller.body(), "\"subject\":\"my-app\"")
    }

    /** Signs in through the mock provider with the groups `team-a`, and gives the start page. */
    private fun signIn(username: String): String =
        get(location(get(callback("$folio/oauth2/authorization/folio", username)))).body()

    /** Starts the sign-in at [authorization] and gives the callback URL of Folio after the login at the provider. */
    private fun callback(authorization: String, username: String): String {
        val authorize = location(get(authorization))
        assertTrue(authorize.startsWith("${provider.issuer()}/authorize?"), authorize)
        assertContains(authorize, "code_challenge_method=S256")

        val login = form(mapOf("username" to username, "claims" to """{"groups": ["team-a"]}"""))
        return location(browser.send(post(authorize, login), STRING))
    }

    private fun get(url: String) = browser.send(HttpRequest.newBuilder(URI.create(url)).build(), STRING)

    private fun post(url: String, form: String) = HttpRequest
        .newBuilder(URI.create(url))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()

    private fun form(fields: Map<String, String>) =
        fields.entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }

    private fun location(response: HttpResponse<String>): String {
        assertEquals(302, response.statusCode(), response.body())
        return response.headers().firstValue("Location").orElseThrow()
    }

    private companion object {
        val STRING: HttpResponse.BodyHandler<String> = HttpResponse.BodyHandlers.ofString()
        val CSRF_FIELD = Regex("<input type=\"hidden\" name=\"_csrf\" value=\"([^\"]+)\">")
        const val SIGN_IN_LINK = """<a href="/sign-in">Sign in</a>"""

        /**
         * Spring Session stores the time of the last access with millisecond precision. The test waits more than one
         * millisecond, so an access through the API would change the stored time.
         */
        const val SESSION_CLOCK_STEP_MILLIS = 20L
    }
}
