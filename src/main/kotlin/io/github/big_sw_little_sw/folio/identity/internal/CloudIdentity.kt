package io.github.big_sw_little_sw.folio.identity.internal

import com.google.auth.oauth2.GoogleCredentials
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * The client of the Cloud Identity Groups API (design 6.5, 19). It finds the groups of a person by email address, and
 * hides the HTTP calls, the access token, the query syntax and the pages of the API.
 *
 * Each call has a connect timeout and a read timeout. Thus a slow API cannot hold a request thread for long.
 */
class CloudIdentity(
    baseUrl: String,
    credentials: GoogleCredentials,
    /**
     * If true, the client calls `searchTransitiveGroups`, and transitive memberships count. If false, the client calls
     * `searchDirectGroups`, and only direct memberships count.
     */
    private val nestedGroups: Boolean,
) {
    private val client =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()).apply {
                    setReadTimeout(READ_TIMEOUT)
                },
            ).requestInterceptor { request, body, execution ->
                // GoogleCredentials refreshes the access token when the token expires. The request metadata can also
                // contain a quota project header, so the interceptor adds each header, not only Authorization.
                accessMetadata(credentials, request.uri).forEach { (name, values) ->
                    request.headers.addAll(name, values)
                }
                execution.execute(request, body)
            }.build()

    // A failed refresh is a fault of the credentials, not of the API. Without this catch, RestClient reports the
    // failure as an API that did not answer. The message of the IOException can contain a response of the token
    // endpoint, so the exception type is the only value that is safe to log.
    @Suppress("SwallowedException")
    private fun accessMetadata(credentials: GoogleCredentials, uri: URI): Map<String, List<String>> = try {
        credentials.getRequestMetadata(uri)
    } catch (exception: IOException) {
        throw DirectoryUnavailable(
            "Application Default Credentials gave no access token: ${exception.javaClass.name}",
        )
    }

    /**
     * Returns the IDs of the groups of the person with [email]. The ID of the group `groups/<id>` is `<id>`, which
     * rules name as `group:<id>`.
     *
     * Throws [DirectoryUnavailable] if a call fails or if the result has more than [MAX_PAGES] pages. This function
     * never returns a part of the groups, because a missing group can change a decision.
     */
    fun groups(email: String): Set<String> {
        val method = if (nestedGroups) "searchTransitiveGroups" else "searchDirectGroups"
        val query = query(email, nestedGroups)
        val groups = mutableSetOf<String>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val page = page(method, query, pageToken)
            page.path("memberships").forEach { groups += groupId(it.path("group").asString("")) }
            val next = page.path("nextPageToken").asString("")
            if (next.isEmpty()) return groups
            pageToken = next
        }
        throw DirectoryUnavailable("The Cloud Identity API gave more than $MAX_PAGES pages of groups")
    }

    // A RestClientException can contain the response body, the request URL with the email address, or a response of
    // the token endpoint. The log must never get these values. Thus this function does not keep the original exception
    // as the cause. The new message keeps only the status or the exception types.
    @Suppress("SwallowedException")
    private fun page(method: String, query: String, pageToken: String?): JsonNode = try {
        client
            .get()
            .uri { builder ->
                // The values are URI variables, so the builder encodes each character that has a meaning in a URI.
                // Thus an email address cannot change the path or add a query parameter.
                builder.path("/v1/groups/-/memberships:{method}").queryParam("query", "{query}")
                if (pageToken != null) builder.queryParam("pageToken", "{pageToken}")
                builder.build(mapOf("method" to method, "query" to query, "pageToken" to pageToken))
            }.retrieve()
            .body<JsonNode>() ?: throw DirectoryUnavailable("The Cloud Identity API gave an empty response")
    } catch (exception: RestClientResponseException) {
        throw DirectoryUnavailable("The Cloud Identity API answered with status ${exception.statusCode.value()}")
    } catch (exception: ResourceAccessException) {
        throw DirectoryUnavailable("The Cloud Identity API did not answer: ${exception.cause?.javaClass?.name}")
    } catch (exception: RestClientException) {
        throw DirectoryUnavailable(
            "The Cloud Identity API gave a response that Folio cannot read: ${exception.javaClass.name} " +
                "(${exception.cause?.javaClass?.name})",
        )
    }

    private fun groupId(resourceName: String): String {
        val id = resourceName.removePrefix(GROUP_PREFIX)
        if (id == resourceName || id.isEmpty() || '/' in id) {
            throw DirectoryUnavailable("The Cloud Identity API gave a group that is not $GROUP_PREFIX<id>")
        }
        return id
    }

    companion object {
        private const val CLOUD_IDENTITY_URL = "https://cloudidentity.googleapis.com"
        private const val SCOPE = "https://www.googleapis.com/auth/cloud-identity.groups.readonly"
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

        /**
         * The limit stops a lookup if the API never ends the result. With the default page size of 200, the limit is
         * 20,000 groups.
         */
        private const val MAX_PAGES = 100
        private const val GROUP_PREFIX = "groups/"

        /**
         * `searchTransitiveGroups` needs a label in its query. All Google Groups have this label, so the query matches
         * each group of the person.
         */
        private const val GROUP_LABEL = "cloudidentity.googleapis.com/groups.discussion_forum"

        /**
         * Makes a client with the credentials of the deployment from Application Default Credentials (design 6.5 step
         * 6). Throws [IllegalStateException] if the credentials are not available, so Folio does not start.
         */
        fun withApplicationDefaultCredentials(nestedGroups: Boolean): CloudIdentity {
            val credentials =
                try {
                    GoogleCredentials.getApplicationDefault().createScoped(SCOPE)
                } catch (exception: IOException) {
                    throw IllegalStateException(
                        "folio.security.directory is google, but Application Default Credentials are not available",
                        exception,
                    )
                }
            return CloudIdentity(CLOUD_IDENTITY_URL, credentials, nestedGroups)
        }

        /**
         * The query is a CEL expression. An email address can contain `'` and `\`, so this function escapes both
         * characters in the string literal. Without the escapes, an email address could change the query.
         */
        private fun query(email: String, nested: Boolean): String {
            val member = "member_key_id == '${email.replace("\\", "\\\\").replace("'", "\\'")}'"
            return if (nested) "$member && '$GROUP_LABEL' in labels" else member
        }
    }
}
