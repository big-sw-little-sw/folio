package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.identity.internal.SignedInUser
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * The caller of the current HTTP request. Only HTTP layers use it. They give the caller to services as a parameter,
 * so services and background work never read the security context.
 */
@Component
class CurrentCaller {
    fun get(): Caller = when (val authentication = SecurityContextHolder.getContext().authentication) {
        null, is AnonymousAuthenticationToken -> Caller.Anonymous
        else -> callerOf(authentication.principal)
    }

    /**
     * An unknown principal is a fault in the sign-in setup. Folio fails the request (500), because an anonymous caller
     * would hide the fault as a permissions problem.
     */
    private fun callerOf(principal: Any?): Caller = when (principal) {
        // A bearer token on /api. The token converter made the caller.
        is Caller.Authenticated -> principal

        // A browser session. Sign-in made the caller.
        is SignedInUser -> principal.caller

        else -> error("No caller for the principal type ${principal?.javaClass?.name}")
    }
}
