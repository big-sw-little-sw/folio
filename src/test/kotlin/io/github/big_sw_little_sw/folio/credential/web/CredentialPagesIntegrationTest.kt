package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCredentials
import io.github.big_sw_little_sw.testing.andExpectProblemPage
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.json
import io.github.big_sw_little_sw.testing.postForm
import io.github.big_sw_little_sw.testing.signedIn
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals

/** These tests check the credential and master-key pages (design 12.3) through MockMvc with signed-in test sessions. */
@FolioIntegrationTest
class CredentialPagesIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val credentials: TestCredentials,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `the Namespace page links to the credentials of the Namespace for a caller with MANAGE`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.READ, "user:alice")
        val link = "/namespaces/${namespace.toApiId()}/credentials"

        mvc.get("/namespaces/${namespace.toApiId()}") { with(signedIn(alice)) }.andExpect {
            content { string(not(containsString(link))) }
        }
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        mvc.get("/namespaces/${namespace.toApiId()}") { with(signedIn(alice)) }.andExpect {
            content { string(containsString("""<a href="$link">Credentials</a>""")) }
        }
    }

    @Test
    fun `the list page shows the credentials of the Namespace and the create form`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val existing = credentials.credential(namespace, "existing")
        val page = "/namespaces/${namespace.toApiId()}/credentials"

        mvc
            .get(page) { with(signedIn(alice)) }
            .andExpect {
                status { isOk() }
                content {
                    string(
                        allOf(
                            containsString(catalog.pathOf(namespace)),
                            containsString("""<a href="/credentials/${existing.id.toApiId()}">existing</a>"""),
                            containsString("""action="$page""""),
                        ),
                    )
                }
            }
    }

    @Test
    fun `the create form creates a credential and goes to its page`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val page = "/namespaces/${namespace.toApiId()}/credentials"

        val created = mvc.postForm(page, alice, "name" to "deploy", "gitInstance" to "github-internal")
        val location =
            created
                .andReturn()
                .response.redirectedUrl
                .orEmpty()
        created.andExpect { redirectedUrlPattern("/credentials/cred_*") }
        mvc.get(location) { with(signedIn(alice)) }.andExpect {
            status { isOk() }
            content { string(containsString("<code>github-internal</code>")) }
        }
    }

    @Test
    fun `a failed create shows the form again with the values and the status of the API`() {
        val namespace = catalog.namespace()
        credentials.credential(namespace, "deploy")
        val page = "/namespaces/${namespace.toApiId()}/credentials"

        mvc
            .postForm(page, SUPER_ADMIN, "name" to "deploy", "gitInstance" to "github")
            .andExpectProblemPage(409, "CREDENTIAL_NAME_TAKEN")
        mvc
            .postForm(page, SUPER_ADMIN, "name" to "Deploy", "gitInstance" to "elsewhere")
            .andExpectProblemPage(400, "INVALID_SLUG")
            .andExpect { content { string(containsString("""value="elsewhere"""")) } }
    }

    @Test
    fun `the credential page shows the public key lines to copy and no private key`() {
        val credential = create()
        val key = credential.keys.single()

        mvc
            .get(url(credential)) { with(signedIn(SUPER_ADMIN)) }
            .andExpect {
                status { isOk() }
                content {
                    string(
                        allOf(
                            containsString(key.publicKeyLine),
                            containsString(key.fingerprint),
                            containsString("${url(credential)}/regenerate"),
                            containsString("${url(credential)}/replace"),
                            containsString("${url(credential)}/disable"),
                            containsString("${url(credential)}/delete"),
                            not(containsString("${url(credential)}/activate")),
                        ),
                    )
                }
            }
    }

    @Test
    fun `the forms rotate the keys of a credential`() {
        val credential = create()
        val page = url(credential)

        mvc.postForm("$page/regenerate", SUPER_ADMIN).andExpect { redirectedUrl(page) }
        val pending = read(credential).json("$.keys[1].id")
        val pendingLine = read(credential).json("$.keys[1].publicKey")
        mvc.get(page) { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(allOf(containsString("$page/activate"), containsString(pendingLine))) }
        }
        mvc.postForm("$page/regenerate", SUPER_ADMIN).andExpectProblemPage(409, "PENDING_KEY_EXISTS")
        mvc.postForm("$page/activate", SUPER_ADMIN, "keyId" to pending).andExpect { redirectedUrl(page) }
        assertEquals("ACTIVE", read(credential).json("$.keys[1].status"))
        mvc.postForm("$page/regenerate", SUPER_ADMIN)
        val discarded = read(credential).json("$.keys[2].id")
        mvc.postForm("$page/discard", SUPER_ADMIN, "keyId" to discarded).andExpect { redirectedUrl(page) }
        read(credential).andExpect {
            jsonPath("$.keys[2].id") { value(discarded) }
            jsonPath("$.keys[2].status") { value("RETIRED") }
            jsonPath("$.keys[1].status") { value("ACTIVE") }
        }
    }

    @Test
    fun `the forms replace, disable and delete a credential`() {
        val credential = create()
        val page = url(credential)

        mvc.postForm("$page/replace", SUPER_ADMIN).andExpect { redirectedUrl(page) }
        read(credential).andExpect { jsonPath("$.keys[*].status") { value(contains("RETIRED", "ACTIVE")) } }
        mvc.postForm("$page/disable", SUPER_ADMIN).andExpect { redirectedUrl(page) }
        assertEquals("DISABLED", read(credential).json("$.status"))
        mvc.get(page) { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(allOf(not(containsString("$page/regenerate")), containsString("$page/delete"))) }
        }

        mvc
            .postForm("$page/delete", SUPER_ADMIN)
            .andExpect { redirectedUrl("/namespaces/${credential.namespaceId.toApiId()}/credentials") }
        mvc.get(page) { with(signedIn(SUPER_ADMIN)) }.andExpectProblemPage(404, "CREDENTIAL_NOT_FOUND")
    }

    /** `MANAGE` does not include `READ` (design 7.1), and only readable resources are links (design 12.3). */
    @Test
    fun `the path of the Namespace is a link only for a caller that can read the Namespace`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val credential = credentials.credential(namespace)
        val link = """<a href="/namespaces/${namespace.toApiId()}">"""
        val pages = listOf(url(credential), "/namespaces/${namespace.toApiId()}/credentials")

        pages.forEach { page ->
            mvc.get(page) { with(signedIn(alice)) }.andExpect {
                status { isOk() }
                content { string(allOf(not(containsString(link)), containsString(catalog.pathOf(namespace)))) }
            }
        }
        catalog.rule(namespace, Action.READ, "user:alice")
        pages.forEach { page ->
            mvc.get(page) { with(signedIn(alice)) }.andExpect { content { string(containsString(link)) } }
        }
    }

    @Test
    fun `the pages need MANAGE, and an anonymous caller goes to sign-in`() {
        val credential = create()
        val list = "/namespaces/${credential.namespaceId.toApiId()}/credentials"

        mvc.get(url(credential)) { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        mvc.get(list) { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        mvc.postForm("${url(credential)}/disable", alice).andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        mvc.get(url(credential)).andExpect { redirectedUrl("/sign-in") }
        mvc.post("${url(credential)}/disable").andExpectProblemPage(403, "CSRF_TOKEN_INVALID")
        assertEquals("ENABLED", read(credential).json("$.status"))
    }

    @Test
    fun `the master-key page is for super admins, and its form runs the re-encryption`() {
        mvc.get("/") { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(containsString("""<a href="/crypto">Master keys</a>""")) }
        }
        mvc
            .get("/crypto") { with(signedIn(SUPER_ADMIN)) }
            .andExpect {
                status { isOk() }
                content {
                    string(
                        allOf(containsString("(active)"), containsString("""action="/crypto/reencrypt"""")),
                    )
                }
            }

        mvc.postForm("/crypto/reencrypt", SUPER_ADMIN).andExpect { redirectedUrl("/crypto") }
        mvc.get("/crypto") { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        mvc.postForm("/crypto/reencrypt", alice).andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
    }

    private fun create(): Credential = credentials.credential()

    /** Reads the state through the API, because the pages show it only as HTML. */
    private fun read(credential: Credential) =
        mvc.get("/api/v1/admin/credentials/${credential.id.toApiId()}") { bearer(SUPER_ADMIN) }

    private fun url(credential: Credential) = "/credentials/${credential.id.toApiId()}"
}
