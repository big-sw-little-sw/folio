package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.security.authentication.BadCredentialsException

/**
 * Makes the caller from the claims of a token. Bearer tokens and the ID tokens of browser sign-in use this one factory,
 * so a person is the same caller in the API and in the UI.
 */
class CallerFactory(private val names: IdentityProperties.ClaimNames, private val directory: Directory) {
    /** Throws [InvalidClaims] if the subject is missing or a claim has the wrong type. */
    fun toCaller(claims: Map<String, Any>): Caller.Authenticated {
        val subject = claims[names.subject]
        if (subject !is String || subject.isBlank()) {
            throw InvalidClaims("Claim '${names.subject}' must be a non-blank string")
        }
        val applicationId = claims[names.applicationId]
        if (applicationId != null && applicationId !is String) {
            throw InvalidClaims("Claim '${names.applicationId}' must be a string")
        }
        return Caller.Authenticated(subject, directory.groups(claims), applicationId)
    }
}

/**
 * The claims of a token give no caller. Spring Security treats the exception as bad credentials: a bearer token gets
 * 401, and a sign-in fails. The message names the claim, never its value, so that it is safe to log.
 */
class InvalidClaims(message: String) : BadCredentialsException(message)
