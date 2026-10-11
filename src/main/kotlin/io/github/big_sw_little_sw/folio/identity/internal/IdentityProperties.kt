package io.github.big_sw_little_sw.folio.identity.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `folio.security`: how Folio reads a caller from a token. */
@ConfigurationProperties("folio.security")
data class IdentityProperties(
    val claims: ClaimNames = ClaimNames(),
    val directory: DirectoryType = DirectoryType.TOKEN,
    val google: Google = Google(),
) {
    init {
        when (directory) {
            // Without this check, a deployment that sets `folio.security.google` but forgets the directory would accept
            // each Google account, because only the google directory restricts accounts.
            DirectoryType.TOKEN -> {
                require(google == Google()) {
                    "folio.security.google applies only when folio.security.directory is google"
                }
            }

            // The google directory gives groups only to people, and the web UI signs in only people. Without a hosted
            // domain, the google directory would reject each person.
            DirectoryType.GOOGLE -> {
                require(google.hostedDomain != null) {
                    "folio.security.directory google needs folio.security.google.hosted-domain"
                }
            }
        }
    }

    /**
     * The names of the claims that give the caller. Entra ID needs `subject: oid`, because Entra gives a different
     * `sub` to each application.
     */
    data class ClaimNames(
        val subject: String = "sub",
        val groups: String = "groups",
        val applicationId: String = "azp",
    ) {
        init {
            require(subject.isNotBlank() && groups.isNotBlank() && applicationId.isNotBlank()) {
                "folio.security.claims names must not be blank"
            }
        }
    }

    /** The origin of the groups of a caller. */
    enum class DirectoryType {
        TOKEN,
        GOOGLE,
    }

    /** `folio.security.google`: the account restrictions and the group lookup of the google directory (design 6.5). */
    data class Google(
        /** A person's token must have this value in its `hd` claim. */
        val hostedDomain: String? = null,
        /** A workload token must have an `email` that ends with `@<project>.iam.gserviceaccount.com`. */
        val serviceAccountProjects: Set<String> = emptySet(),
        /**
         * If true, transitive memberships count. If false, only direct memberships count. Set false on editions
         * without `searchTransitiveGroups`, because only Workspace Enterprise and Cloud Identity Premium have it.
         */
        val nestedGroups: Boolean = true,
        val groupCacheTtl: Duration = DEFAULT_GROUP_CACHE_TTL,
    ) {
        init {
            require(hostedDomain == null || hostedDomain.isNotBlank()) {
                "folio.security.google.hosted-domain must not be blank"
            }
            require(serviceAccountProjects.none { it.isBlank() || '@' in it }) {
                "folio.security.google.service-account-projects must list project IDs"
            }
            require(groupCacheTtl.isPositive) { "folio.security.google.group-cache-ttl must be positive" }
        }

        private companion object {
            val DEFAULT_GROUP_CACHE_TTL: Duration = Duration.ofMinutes(5)
        }
    }
}

/** `folio.ui`: the browser sessions of the web UI. */
@ConfigurationProperties("folio.ui")
data class UiProperties(
    /** A session ends after this time without use. */
    val sessionTimeout: Duration = Duration.ofHours(1),
) {
    init {
        require(sessionTimeout.isPositive) { "folio.ui.session-timeout must be positive" }
    }
}
