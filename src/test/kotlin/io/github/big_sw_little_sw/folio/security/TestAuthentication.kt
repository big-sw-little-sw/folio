package io.github.big_sw_little_sw.folio.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/** Matches `folio.super-admins` in src/test/resources/config/application.yaml. */
const val SUPER_ADMIN = "super-admin"

/** Authenticates the calling thread with a JWT carrying the default claim names, as the resource server would. */
fun authenticateAs(
    subject: String,
    groups: List<String> = emptyList(),
) {
    val jwt =
        Jwt
            .withTokenValue("test-token")
            .header("alg", "none")
            .subject(subject)
            .claim("groups", groups)
            .build()
    SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
}
