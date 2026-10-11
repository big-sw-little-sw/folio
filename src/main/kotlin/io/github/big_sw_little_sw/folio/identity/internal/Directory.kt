package io.github.big_sw_little_sw.folio.identity.internal

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.security.authentication.AuthenticationServiceException
import java.net.URI

/**
 * Gives the groups of an authenticated caller. `folio.security.directory` selects one adapter. Only the identity module
 * knows the directory, so other modules see only the groups in the caller.
 *
 * An adapter for an issuer that signs tokens for accounts outside the organization also restricts the accounts. Thus
 * each token goes through the restriction before it gets groups.
 */
interface Directory {
    /**
     * Throws [InvalidClaims] if the claims do not give groups in the form that this directory expects, or if the
     * claims name an account that this directory does not accept. Throws [DirectoryUnavailable] if the directory
     * cannot give the groups now. Folio never continues without groups, because a missing group can change a decision.
     */
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

/**
 * The directory cannot give the groups now. The token can still be valid, so the client gets status 503, not 401.
 * Spring Security passes the exception to the failure handlers of both filter chains. The message names the cause
 * without a token, an email address or a response body, so that the message is safe to log.
 */
class DirectoryUnavailable(override val message: String) : AuthenticationServiceException(message)

/** Makes the one directory that `folio.security.directory` selects. */
@Configuration(proxyBeanMethods = false)
class Directories {
    @Bean
    fun directory(
        properties: IdentityProperties,
        resourceServer: OAuth2ResourceServerProperties,
        cloudIdentity: ObjectProvider<CloudIdentity>,
    ): Directory = when (properties.directory) {
        IdentityProperties.DirectoryType.TOKEN -> {
            // The Google issuer signs tokens for all Google accounts, and only the google directory restricts the
            // accounts. Without this check, the token directory would accept each Google account.
            require(resourceServer.jwt.issuerUri?.let(::isGoogle) != true) {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri names Google, so " +
                    "folio.security.directory must be google"
            }
            TokenDirectory(properties.claims.groups)
        }

        IdentityProperties.DirectoryType.GOOGLE -> {
            GoogleDirectory(properties.google, cloudIdentity.getObject())
        }
    }

    /**
     * The Cloud Identity client is lazy, because only the google directory needs Application Default Credentials.
     * Thus a deployment with the token directory never creates the client. The google directory creates the client at
     * startup, so Folio does not start without the credentials.
     */
    @Bean
    @Lazy
    fun cloudIdentity(properties: IdentityProperties): CloudIdentity =
        CloudIdentity.withApplicationDefaultCredentials(properties.google.nestedGroups)

    private fun isGoogle(issuer: String): Boolean =
        runCatching { URI.create(issuer).host }.getOrNull().equals(GOOGLE_ISSUER_HOST, ignoreCase = true)

    private companion object {
        const val GOOGLE_ISSUER_HOST = "accounts.google.com"
    }
}
