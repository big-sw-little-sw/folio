package io.github.big_sw_little_sw.testing

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.big_sw_little_sw.folio.identity.internal.CloudIdentity
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * A fake Cloud Identity Groups API that runs in the test process. It serves `searchTransitiveGroups` and
 * `searchDirectGroups` in pages, as Google does, and records each request. Tests give each person groups, and can
 * make the API fail for one person. Thus tests in one application context do not disturb each other.
 *
 * The HTTP server has one thread, so the fake API serves one request at a time. A test of shared lookups uses this
 * limit.
 */
class FakeCloudIdentity : AutoCloseable {
    private val direct = ConcurrentHashMap<String, List<String>>()
    private val nested = ConcurrentHashMap<String, List<String>>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val unreadable = ConcurrentHashMap.newKeySet<String>()
    private val json = JsonMapper.builder().build()
    private val recorded = CopyOnWriteArrayList<Request>()

    /** The requests in the order that the API got them. */
    val requests: List<Request> get() = recorded

    @Volatile
    var pageSize: Int = DEFAULT_PAGE_SIZE

    /** If a test sets the gate, the fake API waits for it before it answers each request. */
    @Volatile
    var gate: CountDownLatch? = null

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/v1/groups/-/") { serve(it) }
            start()
        }

    val url: String = "http://localhost:${server.address.port}"

    /**
     * Gives [email] the [directGroups] and the [nestedGroups], as group IDs. Only `searchTransitiveGroups` gives the
     * nested groups. The call also removes an earlier failure for [email].
     */
    fun member(email: String, directGroups: List<String>, nestedGroups: List<String> = emptyList()) {
        memberWithResourceNames(email, directGroups.map { "groups/$it" })
        nested[email] = nestedGroups.map { "groups/$it" }
    }

    /**
     * Gives [email] groups with the [resourceNames]. Tests use this function for names that are not `groups/<id>`.
     * The call also removes an earlier failure for [email].
     */
    fun memberWithResourceNames(email: String, resourceNames: List<String>) {
        direct[email] = resourceNames
        nested[email] = emptyList()
        failures.remove(email)
        unreadable.remove(email)
    }

    /**
     * Makes the fake API answer each request for [email] with [status]. The body has a detail that the client must not
     * log.
     */
    fun fail(email: String, status: Int = HTTP_UNAVAILABLE) {
        failures[email] = status
    }

    /** Makes the fake API answer each request for [email] with status 200 and a body that is not JSON. */
    fun unreadable(email: String) {
        unreadable += email
    }

    fun requestsFor(email: String): List<Request> = recorded.filter { it.email == email }

    /** Returns a client of the fake API. The access token of the client does not expire during a test. */
    fun client(nestedGroups: Boolean = true): CloudIdentity = CloudIdentity(
        url,
        GoogleCredentials.create(AccessToken(ACCESS_TOKEN, Date.from(Instant.now().plusSeconds(DAY)))),
        nestedGroups,
    )

    override fun close() {
        server.stop(0)
    }

    private fun serve(exchange: HttpExchange) {
        val method = exchange.requestURI.path.substringAfterLast(':')
        val parameters = parameters(exchange.requestURI.rawQuery.orEmpty())
        val query = parameters["query"].orEmpty()
        val email =
            EMAIL
                .find(query)
                ?.groupValues
                ?.get(1)
                ?.replace(ESCAPE, "$1")
        recorded +=
            Request(method, query, email, parameters["pageToken"], exchange.requestHeaders.getFirst("Authorization"))
        gate?.await()
        val failure = email?.let(failures::get)
        when {
            failure != null -> respond(exchange, failure, """{"error": {"message": "secret detail of $email"}}""")
            email == null || !valid(method, query) -> respond(exchange, HTTP_BAD_REQUEST, """{"error": {}}""")
            email in unreadable -> respond(exchange, HTTP_OK, "{not json")
            else -> respond(exchange, HTTP_OK, page(groups(method, email), parameters["pageToken"]))
        }
    }

    /**
     * Rejects a query that the client must not send. `searchTransitiveGroups` needs the group label, as in the real
     * API. `searchDirectGroups` gets no label from the client.
     */
    private fun valid(method: String, query: String): Boolean = when (method) {
        "searchTransitiveGroups" -> "'cloudidentity.googleapis.com/groups.discussion_forum' in labels" in query
        "searchDirectGroups" -> " in labels" !in query
        else -> false
    }

    private fun groups(method: String, email: String): List<String> {
        val directGroups = direct[email].orEmpty()
        return if (method == "searchTransitiveGroups") directGroups + nested[email].orEmpty() else directGroups
    }

    /**
     * The page token is the index of the first group of the page. The memberships have more fields than the client
     * reads, as in the real API.
     */
    private fun page(groups: List<String>, pageToken: String?): String {
        val start = pageToken?.toInt() ?: 0
        val end = minOf(start + pageSize, groups.size)
        val memberships =
            groups.subList(start, end).map {
                mapOf("group" to it, "groupKey" to mapOf("id" to "$it@example.com"), "displayName" to "Group $it")
            }
        val page =
            buildMap<String, Any> {
                put("memberships", memberships)
                if (end < groups.size) put("nextPageToken", end.toString())
            }
        return json.writeValueAsString(page)
    }

    private fun parameters(rawQuery: String): Map<String, String> = rawQuery
        .split('&')
        .filter { it.isNotEmpty() }
        .associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    data class Request(
        val method: String,
        val query: String,
        val email: String?,
        val pageToken: String?,
        val authorization: String?,
    )

    companion object {
        const val ACCESS_TOKEN = "fake-access-token"
        private const val DEFAULT_PAGE_SIZE = 200
        private const val DAY = 86_400L
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAVAILABLE = 503
        private val EMAIL = Regex("""member_key_id == '((?:[^'\\]|\\.)*)'""")
        private val ESCAPE = Regex("""\\(.)""")
    }
}

/**
 * Replaces the Cloud Identity client of the google directory with a client of [FakeCloudIdentity]. Thus the tests need
 * no Application Default Credentials and no Google API. Spring Boot does not let one bean override another bean, so
 * [WithoutRealClient] removes the bean definition of the real client before Spring creates beans.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(FakeCloudIdentityConfiguration.WithoutRealClient::class)
class FakeCloudIdentityConfiguration {
    @Bean
    fun fakeCloudIdentity(): FakeCloudIdentity = FakeCloudIdentity()

    @Bean
    fun fakeCloudIdentityClient(fake: FakeCloudIdentity): CloudIdentity = fake.client()

    class WithoutRealClient : BeanDefinitionRegistryPostProcessor {
        override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
            registry.removeBeanDefinition("cloudIdentity")
        }
    }
}
