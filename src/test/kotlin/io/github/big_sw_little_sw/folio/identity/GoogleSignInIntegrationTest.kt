package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.MockOidcProvider
import io.github.big_sw_little_sw.folio.MockOidcProviderConfiguration
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.OidcBrowser.Companion.SIGN_IN_LINK
import io.github.big_sw_little_sw.testing.OidcBrowser.Companion.location
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.*

/**
 * These tests sign people in to the UI with the google directory. The mock OIDC provider gives ID tokens with the `hd`
 * and `email` claims of Google, and [FakeCloudIdentity] gives the groups. This test class has its own application
 * context, because it sets the google directory.
 */
@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "folio.security.directory=google",
        "folio.security.google.hosted-domain=example.com",
        "spring.security.oauth2.client.registration.folio.scope=openid,email",
    ],
)
@Import(
    TestcontainersConfiguration::class,
    MockOidcProviderConfiguration::class,
    FakeCloudIdentityConfiguration::class,
    NoSyncSchedule::class,
)
@ExtendWith(OutputCaptureExtension::class)
class GoogleSignInIntegrationTest(
    @Value("\${local.server.port}") port: Int,
    @Autowired provider: MockOidcProvider,
    @Autowired private val api: FakeCloudIdentity,
) {
    private val folio = "http://localhost:$port"
    private val browser = OidcBrowser(folio, provider.issuer())

    @Test
    fun `a person of the hosted domain signs in, and the session keeps the groups from Cloud Identity`() {
        val email = person()
        api.member(email, listOf("01team"))

        val callback = browser.signIn("carol", claims("example.com", email))

        assertEquals("$folio/", location(callback))
        assertContains(browser.get("$folio/me").body(), "<code>01team</code>")
        // The session keeps the caller from sign-in. Thus a later failure of the API does not change the groups of the
        // session.
        api.fail(email)
        assertContains(browser.get("$folio/me").body(), "<code>01team</code>")
        assertEquals(1, api.requestsFor(email).size)
    }

    @Test
    fun `a person of another domain cannot sign in, and the log names the cause`(output: CapturedOutput) {
        val response = browser.signIn("mallory", claims("other.com", "mallory@other.com"))

        assertEquals(401, response.statusCode())
        assertContains(response.body(), "<code>SIGN_IN_FAILED</code>")
        assertContains(output.all, "Sign-in failed: Claim 'hd' does not name")
        assertFalse("mallory@other.com" in output.all)
    }

    @Test
    fun `a sign-in while the API fails shows DIRECTORY_UNAVAILABLE`(output: CapturedOutput) {
        val email = person()
        api.fail(email)

        val response = browser.signIn("dave", claims("example.com", email))

        assertEquals(503, response.statusCode())
        assertContains(response.body(), "<code>DIRECTORY_UNAVAILABLE</code>")
        assertContains(output.all, "Sign-in failed: The Cloud Identity API answered with status 503")
        assertFalse("secret detail" in output.all)
        assertContains(browser.get("$folio/").body(), SIGN_IN_LINK)
    }

    private fun person(): String = "p-${UUID.randomUUID()}@example.com"

    private fun claims(hostedDomain: String, email: String) = """{"hd": "$hostedDomain", "email": "$email"}"""
}
