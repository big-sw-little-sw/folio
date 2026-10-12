package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*

/** Sends a bearer token from [TestIssuer] with the claims of [caller]. */
fun MockHttpServletRequestDsl.bearer(caller: Caller.Authenticated) {
    val claims =
        buildMap<String, Any> {
            put("sub", caller.subject)
            put("groups", caller.groups.toList())
            caller.applicationId?.let { put("azp", it) }
        }
    bearer(TestIssuer.token(claims))
}

fun MockHttpServletRequestDsl.bearer(token: String) {
    header(HttpHeaders.AUTHORIZATION, "Bearer $token")
}

/**
 * Sends an API request with the bearer token of [caller] and the JSON [body]. A null [caller] sends no token. The
 * caller has no default value, so that a test that forgets the caller does not pass the authorization by accident.
 */
fun MockMvc.send(
    method: HttpMethod,
    url: String,
    caller: Caller.Authenticated?,
    body: String? = null,
): ResultActionsDsl = request(method, url) {
    if (caller != null) bearer(caller)
    if (body != null) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
}
