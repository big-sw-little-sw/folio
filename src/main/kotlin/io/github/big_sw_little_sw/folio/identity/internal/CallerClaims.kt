package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller

/**
 * Makes the caller from the claims of a token. Bearer tokens and the ID tokens of browser sign-in use the same claim
 * names and the same directory (design 6.2).
 */
class CallerClaims(private val names: IdentityProperties.ClaimNames, private val directory: Directory) {
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

/** The message names the claim, never its value, so that it is safe to log. */
class InvalidClaims(override val message: String) : RuntimeException(message)
