package io.github.big_sw_little_sw.folio.identity.internal

import com.github.benmanes.caffeine.cache.Ticker
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import io.github.big_sw_little_sw.testing.*
import org.junit.jupiter.api.AfterEach
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** These tests use the google directory with a fake Cloud Identity API in the test process. */
class GoogleDirectoryTest {
    private val api = FakeCloudIdentity()
    private val ticker = TestTicker()
    private val directory = directory()

    @AfterEach
    fun stop() = api.close()

    @Test
    fun `a person in the hosted domain gets the transitive groups as group IDs`() {
        api.member(ALICE, listOf("01abc", "02def"), nestedGroups = listOf("03ghi"))

        assertEquals(setOf("01abc", "02def", "03ghi"), directory.groups(person(ALICE)))

        val request = api.requests.single()
        assertEquals("searchTransitiveGroups", request.method)
        assertEquals(
            "member_key_id == '$ALICE' && 'cloudidentity.googleapis.com/groups.discussion_forum' in labels",
            request.query,
        )
        assertEquals("Bearer ${FakeCloudIdentity.ACCESS_TOKEN}", request.authorization)
    }

    @Test
    fun `without nested groups, only direct memberships count`() {
        api.member(ALICE, listOf("01abc"), nestedGroups = listOf("03ghi"))

        assertEquals(setOf("01abc"), directory(api.client(nestedGroups = false)).groups(person(ALICE)))

        val request = api.requests.single()
        assertEquals("searchDirectGroups", request.method)
        assertEquals("member_key_id == '$ALICE'", request.query)
    }

    @Test
    fun `the lookup reads all pages`() {
        api.pageSize = 2
        api.member(ALICE, listOf("g1", "g2", "g3", "g4", "g5"))

        assertEquals(setOf("g1", "g2", "g3", "g4", "g5"), directory.groups(person(ALICE)))

        assertEquals(listOf(null, "2", "4"), api.requests.map { it.pageToken })
    }

    @Test
    fun `a result with more pages than the limit fails, because a part of the groups is never a result`() {
        api.pageSize = 1
        api.member(ALICE, (0..MAX_PAGES).map { "g$it" })

        assertFailsWith<DirectoryOutage> { directory.groups(person(ALICE)) }
        assertEquals(MAX_PAGES, api.requests.size)
    }

    @Test
    fun `a group that is not groups-slash-id fails the lookup`() {
        listOf("teams/x", "groups/", "groups/a/b", "").forEach { name ->
            val email = "p${name.hashCode()}@example.com"
            api.memberWithResourceNames(email, listOf("groups/ok", name))

            assertFailsWith<DirectoryOutage>(name) { directory.groups(person(email)) }
        }
    }

    @Test
    fun `an email address with quotes and backslashes is a safe string in the query`() {
        val email = """o'brien\x@example.com"""
        api.member(email, listOf("01abc"))

        assertEquals(setOf("01abc"), directory.groups(person(email)))

        assertContains(api.requests.single().query, """member_key_id == 'o\'brien\\x@example.com'""")
    }

    @Test
    fun `the cache gives the groups until the time to live ends`() {
        api.member(ALICE, listOf("old"))
        assertEquals(setOf("old"), directory.groups(person(ALICE)))
        api.member(ALICE, listOf("new"))

        ticker.advance(TTL.minusSeconds(1))
        assertEquals(setOf("old"), directory.groups(person(ALICE)))
        assertEquals(1, api.requests.size)

        ticker.advance(Duration.ofSeconds(2))
        assertEquals(setOf("new"), directory.groups(person(ALICE)))
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `an API failure without a cache entry fails the lookup, and the failure does not stay in the cache`() {
        api.fail(ALICE)

        val failure = assertFailsWith<DirectoryOutage> { directory.groups(person(ALICE)) }
        assertEquals("The Cloud Identity API answered with status 503", failure.message)

        api.member(ALICE, listOf("01abc"))
        assertEquals(setOf("01abc"), directory.groups(person(ALICE)))
    }

    @Test
    fun `an API failure after the time to live fails the lookup, and the old groups are not used`() {
        api.member(ALICE, listOf("01abc"))
        directory.groups(person(ALICE))
        api.fail(ALICE)

        ticker.advance(TTL.plusSeconds(1))

        assertFailsWith<DirectoryOutage> { directory.groups(person(ALICE)) }
    }

    @Test
    fun `an API that does not answer fails the lookup`() {
        val stopped = FakeCloudIdentity()
        val client = stopped.client()
        stopped.close()

        val failure = assertFailsWith<DirectoryOutage> { directory(client).groups(person(ALICE)) }
        assertContains(failure.message, "did not answer")
    }

    @Test
    fun `credentials that give no access token fail the lookup, and the message has no response`() {
        val credentials =
            object : GoogleCredentials() {
                override fun refreshAccessToken(): AccessToken = throw IOException("secret token endpoint body")
            }
        val client = CloudIdentity(api.url, credentials, nestedGroups = true)

        val failure = assertFailsWith<DirectoryOutage> { directory(client).groups(person(ALICE)) }
        assertContains(failure.message, "Application Default Credentials gave no access token")
        assertFalse("secret" in failure.message, failure.message)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `a response that is not JSON fails the lookup`() {
        api.unreadable(ALICE)

        val failure = assertFailsWith<DirectoryOutage> { directory.groups(person(ALICE)) }
        assertContains(failure.message, "The Cloud Identity API gave a response that Folio cannot read")
    }

    @Test
    fun `requests of one person at the same time share one lookup`() {
        api.member(ALICE, listOf("01abc"))
        val gate = CountDownLatch(1)
        api.gate = gate
        val results = ConcurrentLinkedQueue<Set<String>>()

        val callers =
            (1..PARALLEL_REQUESTS).map { Thread.ofVirtual().start { results += directory.groups(person(ALICE)) } }
        // The first request waits at the gate of the fake API, so no lookup can end. The fake API serves one request
        // at a time. Without a shared lookup, a second caller would send its own request, and the fake API would
        // record that request after the gate opens.
        awaitUntil("each caller waits for a lookup") {
            api.requests.size == 1 && callers.all { it.state == Thread.State.WAITING }
        }
        gate.countDown()
        callers.forEach { assertTrue(it.join(TIMEOUT), "A caller did not end") }

        assertEquals(List(PARALLEL_REQUESTS) { setOf("01abc") }, results.toList())
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `the hosted domain is not case-sensitive`() {
        api.member(ALICE, listOf("01abc"))

        assertEquals(setOf("01abc"), directory.groups(mapOf("hd" to "Example.COM", "email" to ALICE)))
    }

    @Test
    fun `a person of another domain or without a domain is rejected, and the message shows no value`() {
        listOf(
            mapOf("hd" to "other.com", "email" to "mallory@other.com"),
            mapOf("email" to "mallory@gmail.com"),
            mapOf("hd" to listOf("example.com"), "email" to ALICE),
            emptyMap(),
        ).forEach { claims ->
            val message = assertFailsWith<InvalidClaims>(claims.toString()) {
                directory.groups(claims)
            }.message.orEmpty()
            assertFalse("other.com" in message || "gmail" in message, message)
        }
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `a person needs an email claim`() {
        listOf(mapOf("hd" to DOMAIN), mapOf("hd" to DOMAIN, "email" to " "), mapOf("hd" to DOMAIN, "email" to 1))
            .forEach { claims -> assertFailsWith<InvalidClaims>(claims.toString()) { directory.groups(claims) } }
    }

    @Test
    fun `a workload of a configured project gets no groups and needs no lookup`() {
        assertEquals(emptySet(), directory.groups(workload("deployer@$PROJECT.iam.gserviceaccount.com")))
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `a workload needs a verified email address`() {
        val email = "deployer@$PROJECT.iam.gserviceaccount.com"
        listOf(
            mapOf("email" to email),
            mapOf("email" to email, "email_verified" to false),
            mapOf("email" to email, "email_verified" to "true"),
        ).forEach { claims ->
            assertFailsWith<InvalidClaims>(claims.toString()) { directory.groups(claims) }
        }
    }

    @Test
    fun `a workload of another project is rejected`() {
        listOf(
            "deployer@other.iam.gserviceaccount.com",
            "deployer@x$PROJECT.iam.gserviceaccount.com",
            "deployer@$PROJECT.iam.gserviceaccount.com.evil.test",
            "deployer@.iam.gserviceaccount.com",
            "$PROJECT.iam.gserviceaccount.com",
            "$PROJECT@appspot.gserviceaccount.com",
        ).forEach { email ->
            assertFailsWith<InvalidClaims>(email) { directory.groups(workload(email)) }
        }
    }

    @Test
    fun `without service account projects, each workload is rejected`() {
        val peopleOnly = directory(projects = emptySet())

        assertFailsWith<InvalidClaims> { peopleOnly.groups(workload("deployer@$PROJECT.iam.gserviceaccount.com")) }
    }

    private fun directory(client: CloudIdentity = api.client(), projects: Set<String> = setOf(PROJECT)) =
        GoogleDirectory(
            IdentityProperties.Google(hostedDomain = DOMAIN, serviceAccountProjects = projects, groupCacheTtl = TTL),
            client,
            ticker,
        )

    private fun person(email: String): Map<String, Any> = mapOf("sub" to "1234", "hd" to DOMAIN, "email" to email)

    private fun workload(email: String): Map<String, Any> = mapOf("email" to email, "email_verified" to true)

    /** The ticker moves only when a test moves it. Caffeine reads the ticker from several threads, so it is atomic. */
    private class TestTicker : Ticker {
        private val nanos = AtomicLong()

        override fun read(): Long = nanos.get()

        fun advance(duration: Duration) {
            nanos.addAndGet(duration.toNanos())
        }
    }

    private companion object {
        const val DOMAIN = "example.com"
        const val ALICE = "alice@example.com"
        const val PROJECT = "workloads"
        const val PARALLEL_REQUESTS = 10
        val TTL: Duration = Duration.ofMinutes(5)

        /** A copy of the page limit of [CloudIdentity]. A change of the limit makes the test fail. */
        const val MAX_PAGES = 100
    }
}
