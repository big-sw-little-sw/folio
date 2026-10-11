package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockHttpServletRequestDsl

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
