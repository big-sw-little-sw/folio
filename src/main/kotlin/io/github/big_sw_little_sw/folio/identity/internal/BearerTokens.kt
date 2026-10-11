package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException

/**
 * Makes the caller of an API request from a JWT that Spring Security already validated. A token with claims that
 * give no caller is not valid (401), so that services never see it.
 */
class BearerTokenConverter(private val callerClaims: CallerClaims) : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken = try {
        BearerAuthentication(callerClaims.toCaller(jwt.claims), jwt)
    } catch (exception: InvalidClaims) {
        throw InvalidBearerTokenException(exception.message, exception)
    }
}

/** The authentication of an API request. Its principal is the caller. */
class BearerAuthentication(private val caller: Caller.Authenticated, private val jwt: Jwt) :
    AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Caller.Authenticated = caller

    override fun getCredentials(): Jwt = jwt

    override fun getName(): String = caller.subject
}

/**
 * Spring Boot's decoder reads the discovery document of the issuer at its first use. If that read fails, the token can
 * still be valid. A plain [JwtException] makes Spring report an issuer outage, as for signing keys that it cannot get.
 */
class IssuerOutages(private val decoder: JwtDecoder) : JwtDecoder {
    override fun decode(token: String): Jwt = try {
        decoder.decode(token)
    } catch (exception: JwtDecoderInitializationException) {
        throw JwtException("The discovery document of the issuer is not available", exception)
    }
}
