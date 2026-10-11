package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException

/** Failures of the security filters. The filters give them to the web module, so they have the usual error body. */
sealed class IdentityException(kind: ErrorKind, code: String, detail: String) : FolioException(kind, code, detail) {
    /**
     * The bearer token is not valid. For example, its signature, issuer, audience or expiry time is wrong, or it has
     * no subject. The detail does not say which check failed.
     */
    class InvalidToken :
        IdentityException(ErrorKind.UNAUTHENTICATED, "INVALID_TOKEN", "The bearer token is not valid.")

    /**
     * Folio cannot validate a bearer token, because the issuer did not give its discovery document or its signing
     * keys. The token can be valid, so a client can try again.
     */
    class IssuerUnavailable :
        IdentityException(
            ErrorKind.UNAVAILABLE,
            "ISSUER_UNAVAILABLE",
            "Folio cannot validate the bearer token now, because the identity provider is not available.",
        )

    /** A UI request that changes data had no valid CSRF token, for example because the session ended. */
    class CsrfRejected :
        IdentityException(
            ErrorKind.FORBIDDEN,
            "CSRF_TOKEN_INVALID",
            "The request has no valid CSRF token. Reload the page and try again.",
        )
}
