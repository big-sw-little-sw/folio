package io.github.big_sw_little_sw.folio.security

import io.github.big_sw_little_sw.folio.security.internal.ClaimNames
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/** The principal of the current request, read from the calling thread's security context. */
@Component
class CurrentPrincipal(
    private val claimNames: ClaimNames,
) {
    fun get(): ApplicationPrincipal =
        when (val authentication = SecurityContextHolder.getContext().authentication) {
            is JwtAuthenticationToken -> claimNames.toPrincipal(authentication.token.claims)

            // Spring's anonymous token, or no authentication at all.
            else -> ApplicationPrincipal.Anonymous
        }
}
