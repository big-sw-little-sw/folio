package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException
import org.springframework.security.oauth2.jwt.JwtException

/**
 * The authentication of an API request. Its principal is the caller. Nothing reads the token after conversion, so the
 * authentication does not keep the token.
 */
class BearerAuthentication(private val caller: Caller.Authenticated) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Caller.Authenticated = caller

    override fun getCredentials(): Any? = null

    override fun getName(): String = caller.subject
}

/**
 * Spring Boot's decoder reads the discovery document of the issuer at its first use. If that read fails, the decoder
 * throws an exception that Spring Security does not handle, and the response is a 500. The token can still be valid,
 * so this decoder throws a plain [JwtException]. Spring then reports an issuer outage, as for missing signing keys.
 */
class OutageReportingJwtDecoder(private val decoder: JwtDecoder) : JwtDecoder {
    override fun decode(token: String): Jwt = try {
        decoder.decode(token)
    } catch (exception: JwtDecoderInitializationException) {
        throw JwtException("The discovery document of the issuer is not available", exception)
    }
}
