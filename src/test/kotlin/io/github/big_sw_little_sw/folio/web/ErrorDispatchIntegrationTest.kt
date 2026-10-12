package io.github.big_sw_little_sw.folio.web

import io.github.big_sw_little_sw.testing.*
import org.springframework.beans.factory.annotation.Value
import java.net.http.HttpResponse
import kotlin.test.*

/**
 * Errors outside Spring MVC go to the `/error` dispatch of the servlet container. MockMvc has no such dispatch, so
 * these tests use a real port. The tests cause errors in two ways. The Spring Security firewall rejects a path
 * parameter before any controller runs. [TestFilter] fails other requests.
 */
@FolioServerTest
class ErrorDispatchIntegrationTest(@Value("\${local.server.port}") private val serverPort: Int) {
    @Test
    fun `a request that the firewall rejects has the usual problem detail on the API`() {
        val response = httpGet(serverPort, "/api/test/caller;x=y")

        assertProblem(response, 400, "INVALID_REQUEST")
        assertContains(response.body(), "\"instance\":\"/api/test/caller;x=y\"")
    }

    @Test
    fun `a request that the firewall rejects has the usual error page in the UI`() {
        val response = httpGet(serverPort, "/test;x=y")

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
        val response = httpGet(serverPort, TestFilter.FAILURE)

        assertProblem(response, 409, "TEST_CONFLICT")
        assertContains(response.body(), "\"detail\":\"A test failure of kind CONFLICT.\"")
        assertContains(response.body(), "\"instance\":\"${TestFilter.FAILURE}\"")
    }

    @Test
    fun `a server error other than 500 has the name of its status as the code`() {
        assertProblem(httpGet(serverPort, TestFilter.SEND_ERROR), 503, "SERVICE_UNAVAILABLE")
    }

    private fun assertProblem(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode())
        assertEquals("application/problem+json", header(response, "Content-Type"))
        assertEquals("no-store", header(response, "Cache-Control"))
        assertContains(response.body(), "\"code\":\"$code\"")
        assertTrue("/error" !in response.body(), response.body())
    }

    private fun header(response: HttpResponse<String>, name: String) = response.headers().firstValue(name).orElse("")
}
