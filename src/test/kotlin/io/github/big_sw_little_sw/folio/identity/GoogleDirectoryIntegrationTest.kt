package io.github.big_sw_little_sw.folio.identity

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.testing.FakeCloudIdentity
import io.github.big_sw_little_sw.testing.FakeCloudIdentityConfiguration
import io.github.big_sw_little_sw.testing.NoSyncSchedule
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestIssuer
import io.github.big_sw_little_sw.testing.TestIssuerConfiguration
import io.github.big_sw_little_sw.testing.TestRoutes
import io.github.big_sw_little_sw.testing.TestRoutes.Companion.CALLER
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * These tests check bearer tokens with the google directory (design 6.5). [TestIssuer] signs the tokens with the claims
 * of Google tokens, and [FakeCloudIdentity] gives the groups. Each test uses its own person, because the application
 * context keeps the group cache from test to test. This test class has its own application context, because it sets
 * the google directory.
 */
@Tag("integration")
@SpringBootTest(properties = [DIRECTORY, HOSTED_DOMAIN, PROJECTS, SCOPE])
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    TestIssuerConfiguration::class,
    FakeCloudIdentityConfiguration::class,
    NoSyncSchedule::class,
    TestRoutes::class,
    TestCatalog::class,
)
@ExtendWith(OutputCaptureExtension::class)
class GoogleDirectoryIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val api: FakeCloudIdentity,
    @Autowired private val catalog: TestCatalog,
) {
    @Test
    fun `a person gets the groups from Cloud Identity as group IDs`() {
        val email = person()
        api.member(email, listOf("01team"), nestedGroups = listOf("02parent"))

        mvc.get(CALLER) { bearer(personToken(email)) }.andExpect {
            status { isOk() }
            jsonPath("$.subject") { value("1001") }
            jsonPath("$.groups[0]") { value("01team") }
            jsonPath("$.groups[1]") { value("02parent") }
            jsonPath("$.applicationId") { value(CLI) }
            jsonPath("$.superAdmin") { value(false) }
        }
    }

    @Test
    fun `a group ID from Cloud Identity in folio super-admins makes a super admin`() {
        val email = person()
        api.member(email, listOf("folio-admins"))

        mvc.get(CALLER) { bearer(personToken(email)) }.andExpect {
            status { isOk() }
            jsonPath("$.superAdmin") { value(true) }
        }
    }

    @Test
    fun `a rule for a group ID permits a member of the group`() {
        val email = person()
        api.member(email, listOf("03readers"))
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.READ, "group:03readers")

        mvc.get("/api/v1/admin/namespaces/${namespace.toApiId()}") { bearer(personToken(email)) }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `a workload gets no groups, and a rule names it by its application ID`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.READ, "application:$WORKLOAD_ID")
        val token = workloadToken("deployer@$PROJECT.iam.gserviceaccount.com")

        mvc.get(CALLER) { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.subject") { value(WORKLOAD_ID) }
            jsonPath("$.groups") { isEmpty() }
            jsonPath("$.applicationId") { value(WORKLOAD_ID) }
        }
        mvc.get("/api/v1/admin/namespaces/${namespace.toApiId()}") { bearer(token) }.andExpect { status { isOk() } }
        assertEquals(emptyList(), api.requestsFor("deployer@$PROJECT.iam.gserviceaccount.com"))
    }

    @Test
    fun `accounts outside the restrictions are 401 and the log names the cause`(output: CapturedOutput) {
        listOf(
            TestIssuer.token(mapOf("sub" to "2001", "hd" to "other.com", "email" to "mallory@other.com")),
            TestIssuer.token(mapOf("sub" to "2002", "email" to "mallory@gmail.com")),
            workloadToken("deployer@other-project.iam.gserviceaccount.com"),
        ).forEach { token ->
            mvc.get(CALLER) { bearer(token) }.andExpectProblem(401, "INVALID_TOKEN")
        }

        assertContains(output.all, "A bearer token was refused: Claim 'hd' does not name folio.security.google")
        assertFalse("mallory" in output.all)
    }

    @Test
    fun `an API failure without a cache entry is 503, and the log has no response body`(output: CapturedOutput) {
        val email = person()
        api.fail(email)

        mvc.get(CALLER) { bearer(personToken(email)) }.andExpectProblem(503, "DIRECTORY_UNAVAILABLE")

        assertContains(output.all, "the directory is not available: The Cloud Identity API answered with status 503")
        assertFalse("secret detail" in output.all)
    }

    @Test
    fun `an API failure within the time to live uses the cache entry`() {
        val email = person()
        api.member(email, listOf("01team"))
        mvc.get(CALLER) { bearer(personToken(email)) }.andExpect { status { isOk() } }
        api.fail(email)

        mvc.get(CALLER) { bearer(personToken(email)) }.andExpect {
            status { isOk() }
            jsonPath("$.groups[0]") { value("01team") }
        }
        assertEquals(1, api.requestsFor(email).size)
    }

    /** Returns the email address of a new person in the hosted domain. */
    private fun person(): String = "p-${UUID.randomUUID()}@$DOMAIN"

    /** Returns a Google ID token of a person, as the CLI gets it with the device flow (design 6.5 step 2). */
    private fun personToken(email: String): String =
        TestIssuer.token(mapOf("sub" to "1001", "hd" to DOMAIN, "email" to email, "azp" to CLI))

    /**
     * Returns a Google ID token of a service account (design 6.5 step 7). In this token, `azp` is the unique ID of the
     * service account.
     */
    private fun workloadToken(email: String): String = TestIssuer.token(
        mapOf("sub" to WORKLOAD_ID, "azp" to WORKLOAD_ID, "email" to email, "email_verified" to true),
    )

    private companion object {
        const val DOMAIN = "example.com"
        const val PROJECT = "workloads"
        const val CLI = "cli-client-id"
        const val WORKLOAD_ID = "104729"
    }
}

private const val DIRECTORY = "folio.security.directory=google"
private const val HOSTED_DOMAIN = "folio.security.google.hosted-domain=example.com"
private const val PROJECTS = "folio.security.google.service-account-projects=workloads"
private const val SCOPE = "spring.security.oauth2.client.registration.folio.scope=openid,email"
