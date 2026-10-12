package io.github.big_sw_little_sw.folio.identity.internal

/**
 * Gives the groups of an authenticated caller. `folio.security.directory` selects one adapter. Only the identity module
 * knows the directory, so other modules see only the groups in the caller.
 */
interface Directory {
    /** Throws [InvalidClaims] if the claims do not give groups in the form that this directory expects. */
    fun groups(claims: Map<String, Any>): Set<String>
}

/** The `token` directory reads the groups claim of the token. A token without the claim gives no groups. */
class TokenDirectory(private val groupsClaim: String) : Directory {
    override fun groups(claims: Map<String, Any>): Set<String> {
        val value = claims[groupsClaim] ?: return emptySet()
        if (value !is Collection<*> || value.any { it !is String }) {
            throw InvalidClaims("Claim '$groupsClaim' must be a list of strings")
        }
        return value.filterIsInstance<String>().toSet()
    }
}
