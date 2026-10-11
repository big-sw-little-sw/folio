package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException

/** Failures of the security filters and of the subject format. */
sealed class IdentityException(kind: ErrorKind, code: String, detail: String) : FolioException(kind, code, detail) {
    /**
     * The bearer token is not valid. For example, its signature, issuer, audience or expiry time is wrong, or it has
     * no subject. The detail does not say which check failed, so that it gives no help to an attacker.
     */
    class InvalidToken :
        IdentityException(ErrorKind.UNAUTHENTICATED, "INVALID_TOKEN", "The bearer token is not valid.")

    /**
     * Folio cannot validate a bearer token, because the issuer did not give its discovery document or its signing
     * keys. The token can still be valid, so a client can try again later.
     */
    class IssuerUnavailable :
        IdentityException(
            ErrorKind.UNAVAILABLE,
            "ISSUER_UNAVAILABLE",
            "Folio cannot validate the bearer token now, because the identity provider is not available.",
        )

    /**
     * Folio cannot get the groups of the caller, because the directory is not available. The token can still be
     * valid, so a client can try again later.
     */
    class DirectoryUnavailable :
        IdentityException(
            ErrorKind.UNAVAILABLE,
            "DIRECTORY_UNAVAILABLE",
            "Folio cannot get the groups of the caller now, because the directory is not available.",
        )

    /** Browser sign-in failed, for example because the ID token gives no caller. Only the log has the cause. */
    class SignInFailed :
        IdentityException(
            ErrorKind.UNAUTHENTICATED,
            "SIGN_IN_FAILED",
            "Folio could not sign you in. If this occurs again, ask an administrator to examine the Folio log.",
        )

    /** A UI request that changes data had no valid CSRF token, for example because the session ended. */
    class CsrfRejected :
        IdentityException(
            ErrorKind.FORBIDDEN,
            "CSRF_TOKEN_INVALID",
            "The request has no valid CSRF token. Reload the page and try again.",
        )

    /** A rule names a subject that does not have the subject format. */
    class InvalidSubject :
        IdentityException(
            ErrorKind.INVALID,
            "INVALID_SUBJECT",
            "A subject is public, authenticated, user:<id>, group:<id> or application:<id>.",
        )
}
