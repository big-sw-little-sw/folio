package io.github.big_sw_little_sw.folio.web

import io.github.big_sw_little_sw.testing.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*
import kotlin.test.*

@FolioIntegrationTest
class ErrorResponsesIntegrationTest(@Autowired private val mvc: MockMvc) {
    @ParameterizedTest
    @EnumSource(ErrorKind::class)
    fun `each kind of expected failure has its status and a problem detail with the code`(kind: ErrorKind) {
        mvc.get("/api/test/failures/$kind").andExpectProblem(STATUS.getValue(kind), "TEST_$kind").andExpect {
            jsonPath("$.detail") { value("A test failure of kind $kind.") }
            jsonPath("$.instance") { value("/api/test/failures/$kind") }
        }
    }

    @Test
    fun `an unauthenticated caller is told to send a bearer token`() {
        mvc.get("/api/test/failures/UNAUTHENTICATED").andExpect {
            header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
        }
    }

    @Test
    fun `an unavailable failure can tell the client when to try again`() {
        mvc.get("/api/test/failures/UNAVAILABLE").andExpect {
            header { string(HttpHeaders.RETRY_AFTER, TestRoutes.RETRY_AFTER_SECONDS.toString()) }
        }
    }

    @Test
    fun `an unexpected error is INTERNAL without the exception message`() {
        mvc.get("/api/test/unexpected").andExpectProblem(500, "INTERNAL").andExpect {
            content { string(not(containsString(TestRoutes.SECRET))) }
        }
    }

    @Test
    fun `requests that Spring MVC cannot read are INVALID_REQUEST`() {
        mvc
            .post("/api/test/echo") {
                contentType = MediaType.APPLICATION_JSON
                content = "{not json"
            }.andExpectProblem(400, "INVALID_REQUEST")
        mvc
            .post("/api/test/echo") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpectProblem(400, "INVALID_REQUEST")
        mvc.get("/api/test/count?n=many").andExpectProblem(400, "INVALID_REQUEST")
        mvc.get("/api/test/count").andExpectProblem(400, "INVALID_REQUEST")
    }

    @Test
    fun `an API ID in another form is INVALID_ID`() {
        val id = "tst_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e"
        mvc.get("/api/test/ids/$id").andExpect { content { string(id) } }
        mvc.get("/api/test/ids/${id.uppercase()}").andExpectProblem(400, "INVALID_ID")
    }

    @Test
    fun `other Spring MVC failures have a code from their status`() {
        mvc.get("/api/no-such-route").andExpectProblem(404, "NOT_FOUND")
        mvc.post("/api/test/count?n=1").andExpectProblem(405, "METHOD_NOT_ALLOWED")
    }

    @Test
    fun `a direct request for the error route finds nothing`() {
        mvc.get("/error").andExpectPage(404, "NOT_FOUND")
    }

    @Test
    fun `a UI route shows an error page with the same code and detail`() {
        mvc.get("/test/failures/CONFLICT").andExpectPage(409, "TEST_CONFLICT").andExpect {
            content { string(containsString("A test failure of kind CONFLICT.")) }
        }
        mvc.get("/no-such-page").andExpectPage(404, "NOT_FOUND")
        mvc.get("/test/unexpected").andExpectPage(500, "INTERNAL").andExpect {
            content { string(not(containsString(TestRoutes.SECRET))) }
        }
    }

    private fun ResultActionsDsl.andExpectProblem(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
        jsonPath("$.status") { value(status) }
        jsonPath("$.code") { value(code) }
        jsonPath("$.title") { exists() }
        jsonPath("$.type") { doesNotExist() }
    }

    private fun ResultActionsDsl.andExpectPage(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        content { string(containsString("<code>$code</code>")) }
        header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
        header { stringValues("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'") }
        header { stringValues("X-Content-Type-Options", "nosniff") }
    }

    private companion object {
        val STATUS =
            mapOf(
                ErrorKind.NOT_FOUND to 404,
                ErrorKind.CONFLICT to 409,
                ErrorKind.INVALID to 400,
                ErrorKind.FORBIDDEN to 403,
                ErrorKind.UNAUTHENTICATED to 401,
                ErrorKind.UNAVAILABLE to 503,
                ErrorKind.UPSTREAM_FAILED to 502,
                ErrorKind.UPSTREAM_TIMEOUT to 504,
                ErrorKind.TOO_LARGE to 422,
            )
    }
}
