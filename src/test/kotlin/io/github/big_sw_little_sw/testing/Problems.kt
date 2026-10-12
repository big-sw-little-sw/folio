package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.catalog.Action
import org.hamcrest.Matchers.*
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*

/**
 * Expects an RFC 9457 problem detail with [status] and [code]. A 401 also needs `WWW-Authenticate: Bearer`, so that a
 * client knows which credentials to send.
 */
fun ResultActionsDsl.andExpectProblem(status: Int, code: String): ResultActionsDsl = andExpect {
    status { isEqualTo(status) }
    content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
    header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
    if (status == 401) header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
    jsonPath("$.status") { value(status) }
    jsonPath("$.code") { value(code) }
    jsonPath("$.title") { exists() }
    jsonPath("$.type") { doesNotExist() }
}

/**
 * Expects `ACTION_NOT_ALLOWED` for [action] on [apiId], or on the root if [apiId] is null. The detail names the action
 * and the resource, so that a test shows which check denied the request.
 */
fun ResultActionsDsl.andExpectForbidden(action: Action, apiId: String?): ResultActionsDsl =
    andExpectProblem(403, "ACTION_NOT_ALLOWED").andExpect {
        jsonPath("$.detail") { value("The caller does not have $action on ${apiId ?: "the root"}.") }
    }

/** Expects a UI page with [status] that shows [code]. The page is the error page, or a form that shows the error. */
fun ResultActionsDsl.andExpectProblemPage(status: Int, code: String): ResultActionsDsl = andExpect {
    status { isEqualTo(status) }
    content { string(containsString("<code>$code</code>")) }
}

/** Returns the value at a JSON path of the response body, for example `$.id`. */
fun ResultActionsDsl.json(path: String): String = com.jayway.jsonpath.JsonPath
    .read<Any?>(andReturn().response.contentAsString, path)
    .toString()
