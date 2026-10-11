package io.github.big_sw_little_sw.folio.web

import io.github.big_sw_little_sw.testing.FolioServerTest
import io.github.big_sw_little_sw.testing.TestFilter
import org.springframework.beans.factory.annotation.Value
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Errors outside Spring MVC go to the servlet container's `/error` dispatch. MockMvc has no such dispatch, so this
 * test uses a real port. Spring Security's firewall rejects a path parameter before any controller runs, and
 * [TestFilter] fails other requests.
 */
@FolioServerTest
class ErrorDispatchIntegrationTest(@Value("\${local.server.port}") private val serverPort: Int) {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `a request that the firewall rejects has the usual problem detail on the API`() {
        val response = get("/api/test/caller;x=y")

        assertProblem(response, 400, "INVALID_REQUEST")
        assertContains(response.body(), "\"instance\":\"/api/test/caller;x=y\"")
    }

    @Test
    fun `a request that the firewall rejects has the usual error page in the UI`() {
        val response = get("/test;x=y")

        assertEquals(400, response.statusCode())
        assertTrue(header(response, "Content-Type").startsWith("text/html"), header(response, "Content-Type"))
        assertEquals("no-store", header(response, "Cache-Control"))
        assertEquals(
            listOf("default-src 'self'; frame-ancestors 'none'"),
            response.headers().allValues("Content-Security-Policy"),
        )
        assertEquals(listOf("nosniff"), response.headers().allValues("X-Content-Type-Options"))
        assertContains(response.body(), "<code>INVALID_REQUEST</code>")
    }

    @Test
    fun `a FolioException from a filter keeps its status and code`() {
        val response = get(TestFilter.FAILURE)

        assertProblem(response, 409, "TEST_CONFLICT")
        assertContains(response.body(), "\"detail\":\"A test failure of kind CONFLICT.\"")
        assertContains(response.body(), "\"instance\":\"${TestFilter.FAILURE}\"")
    }

    @Test
    fun `a server error other than 500 has the name of its status as the code`() {
        assertProblem(get(TestFilter.SEND_ERROR), 503, "SERVICE_UNAVAILABLE")
    }

    private fun assertProblem(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode())
        assertEquals("application/problem+json", header(response, "Content-Type"))
        assertEquals("no-store", header(response, "Cache-Control"))
        assertContains(response.body(), "\"code\":\"$code\"")
        assertTrue("/error" !in response.body(), response.body())
    }

    private fun header(response: HttpResponse<String>, name: String) = response.headers().firstValue(name).orElse("")

    private fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$serverPort$path")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
