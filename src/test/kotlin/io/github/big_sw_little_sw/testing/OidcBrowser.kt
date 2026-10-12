package io.github.big_sw_little_sw.testing

import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

/**
 * A test browser with cookies. It signs in to Folio at [folio] through the mock OIDC provider at [issuer].
 * The browser follows no redirects, so tests can examine each step of the authorization code flow.
 */
class OidcBrowser(private val folio: String, private val issuer: String) {
    val cookies = CookieManager()
    private val client = HttpClient.newBuilder().cookieHandler(cookies).build()

    /**
     * Starts the sign-in at [authorization] and logs in at the provider as [username] with the ID token [claims] as
     * JSON. Returns the callback URL of Folio.
     */
    fun callback(authorization: String, username: String, claims: String): String {
        val authorize = location(get(authorization))
        assertTrue(authorize.startsWith("$issuer/authorize?"), authorize)
        assertContains(authorize, "code_challenge_method=S256")

        val login = form(mapOf("username" to username, "claims" to claims))
        return location(client.send(formRequest(authorize, login), STRING))
    }

    /** Signs in as [username] with the ID token [claims], and returns the response of the callback. */
    fun signIn(username: String, claims: String): HttpResponse<String> =
        get(callback("$folio/oauth2/authorization/folio", username, claims))

    fun get(url: String): HttpResponse<String> = client.send(HttpRequest.newBuilder(URI.create(url)).build(), STRING)

    fun post(url: String, fields: Map<String, String>): HttpResponse<String> =
        client.send(formRequest(url, form(fields)), STRING)

    companion object {
        val STRING: HttpResponse.BodyHandler<String> = HttpResponse.BodyHandlers.ofString()

        /** The link of the UI layout for a caller who has not signed in. */
        const val SIGN_IN_LINK = """<a href="/sign-in">Sign in</a>"""

        /** Builds a form request without the cookies of a browser. */
        fun formRequest(url: String, form: String): HttpRequest = HttpRequest
            .newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        fun form(fields: Map<String, String>): String =
            fields.entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }

        fun location(response: HttpResponse<String>): String {
            assertEquals(302, response.statusCode(), response.body())
            return response.headers().firstValue("Location").orElseThrow()
        }
    }
}
