package io.github.big_sw_little_sw.folio.identity.internal

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.util.concurrent.Executors

/**
 * The `google` directory (design 6.5). The Google issuer signs tokens for all Google accounts, so this directory first
 * restricts the accounts (step 5). A token that matches neither restriction gives [InvalidClaims], so Folio rejects the
 * token with status 401 and logs the cause.
 *
 * - A person has `folio.security.google.hosted-domain` in the `hd` claim. The Cloud Identity API gives the groups of
 *   the person for the `email` claim.
 * - A workload has a verified `email` claim that ends with `@<project>.iam.gserviceaccount.com`, for a configured
 *   project. A workload gets no groups. Rules name it as `application:<id>`.
 *
 * The groups of each person stay in a cache for `folio.security.google.group-cache-ttl`. After that time, the entry is
 * gone. If the API then fails, the caller gets [DirectoryUnavailable], not the old groups. Thus the time limit on the
 * groups of a person who left a group always holds. A failed lookup does not stay in the cache.
 */
class GoogleDirectory(
    google: IdentityProperties.Google,
    private val cloudIdentity: CloudIdentity,
    /** Tests give a ticker that they move forward, so the tests do not wait for the time to live to end. */
    ticker: Ticker = Ticker.systemTicker(),
) : Directory {
    // IdentityProperties requires a hosted domain for the google directory. Without a hosted domain, a token without
    // `hd` would match the null value and count as a person.
    private val hostedDomain = checkNotNull(google.hostedDomain)
    private val projects = google.serviceAccountProjects

    /**
     * Each lookup runs on its own virtual thread, so a slow call holds no lock of the cache. Requests of one person
     * share one lookup, so a burst of requests causes one API call.
     *
     * A lookup returns a failure as a value, not as an exception. Caffeine logs each exception of a lookup with its
     * causes. Without this choice, the log could get a response of the token endpoint.
     */
    private val cache: AsyncLoadingCache<String, Result<Set<String>>> =
        Caffeine
            .newBuilder()
            .maximumSize(MAX_PEOPLE)
            .expireAfterWrite(google.groupCacheTtl)
            .ticker(ticker)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .buildAsync { email -> runCatching { cloudIdentity.groups(email) } }

    override fun groups(claims: Map<String, Any>): Set<String> {
        val email = claims[EMAIL]
        return when {
            // Domain names are not case-sensitive, so the comparison ignores case.
            (claims[HOSTED_DOMAIN] as? String).equals(hostedDomain, ignoreCase = true) -> {
                if (email !is String || email.isBlank()) {
                    throw InvalidClaims("Claim '$EMAIL' must be a non-blank string for a person")
                }
                lookUp(email)
            }

            // A Google account can have an unverified address that looks like a service account. Service account
            // tokens always have `email_verified: true`. The JWT decoders give the claim as a Boolean, so a string
            // "true" does not match.
            email is String && claims[EMAIL_VERIFIED] == true && isAllowedWorkload(email) -> {
                emptySet()
            }

            else -> {
                throw InvalidClaims(
                    "Claim '$HOSTED_DOMAIN' does not name folio.security.google.hosted-domain, and claim '$EMAIL' " +
                        "is not a verified service account of folio.security.google.service-account-projects",
                )
            }
        }
    }

    private fun lookUp(email: String): Set<String> {
        val lookup = cache.get(email)
        val result = lookup.join()
        // The requests that wait for this lookup share its failure. Then this function removes the failed lookup from
        // the cache, so the next request of the person calls the API again. The removal names the lookup, so the
        // function never removes a newer lookup that another request started.
        if (result.isFailure) cache.asMap().remove(email, lookup)
        return result.getOrThrow()
    }

    private fun isAllowedWorkload(email: String): Boolean {
        val domain = email.substringAfterLast('@', missingDelimiterValue = "")
        return domain.endsWith(SERVICE_ACCOUNT_DOMAIN) && domain.removeSuffix(SERVICE_ACCOUNT_DOMAIN) in projects
    }

    private companion object {
        const val HOSTED_DOMAIN = "hd"
        const val EMAIL = "email"
        const val EMAIL_VERIFIED = "email_verified"
        const val SERVICE_ACCOUNT_DOMAIN = ".iam.gserviceaccount.com"

        /** The cache keeps the groups of at most this number of people. Caffeine removes the least used entries. */
        const val MAX_PEOPLE = 10_000L
    }
}
