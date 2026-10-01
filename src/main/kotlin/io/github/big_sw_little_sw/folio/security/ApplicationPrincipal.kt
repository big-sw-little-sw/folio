package io.github.big_sw_little_sw.folio.security

/** The caller as policy sees it, independent of how it authenticated (ADR 0002). */
sealed interface ApplicationPrincipal {
    /** A request without a bearer token. */
    data object Anonymous : ApplicationPrincipal

    data class Authenticated(
        val subject: String,
        val groups: Set<String>,
        val applicationId: String?,
    ) : ApplicationPrincipal
}
