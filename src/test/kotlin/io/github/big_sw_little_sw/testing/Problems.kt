package io.github.big_sw_little_sw.testing

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl

/** Expects an RFC 9457 problem detail with [status] and [code] (design 8.6). */
fun ResultActionsDsl.andExpectProblem(status: Int, code: String): ResultActionsDsl = andExpect {
    status { isEqualTo(status) }
    content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
    header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
    jsonPath("$.status") { value(status) }
    jsonPath("$.code") { value(code) }
}

/** The value at a JSON path of the response body, for example `$.id`. */
fun ResultActionsDsl.json(path: String): String = com.jayway.jsonpath.JsonPath
    .read<Any?>(andReturn().response.contentAsString, path)
    .toString()

/** Sends [body] as JSON. */
fun MockHttpServletRequestDsl.jsonBody(body: String) {
    contentType = MediaType.APPLICATION_JSON
    content = body
}
