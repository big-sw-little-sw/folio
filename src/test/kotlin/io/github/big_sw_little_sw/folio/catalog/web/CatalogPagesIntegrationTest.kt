package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.*
import java.util.UUID
import kotlin.test.*

@FolioIntegrationTest
class CatalogPagesIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `a Namespace page shows the path and the children`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search", "The search team")
        val child = catalog.namespace(search, "child")
        val serviceA = catalog.configSet(search, "service-a")
        catalog.rule(top, Action.READ, "user:alice")

        val page = mvc.page("/namespaces/${search.toApiId()}", alice)

        assertContains(page, """<a href="/namespaces/${top.toApiId()}">${catalog.pathOf(top)}</a>""")
        assertContains(page, "<code>${search.toApiId()}</code>")
        assertContains(page, "<code>${catalog.pathOf(top)}/search</code>")
        assertContains(page, "The search team")
        assertContains(page, """<a href="/namespaces/${child.toApiId()}">child</a>""")
        assertContains(page, """<a href="/configsets/${serviceA.toApiId()}">service-a</a>""")
    }

    @Test
    fun `a page shows only the operations that the caller can do`() {
        val search = catalog.namespace()
        catalog.rule(search, Action.READ, "user:alice")
        val page = "/namespaces/${search.toApiId()}"
        val operations = listOf("/namespaces/new?parentId=", "$page/edit", "$page/move", "$page/delete", "$page/rules")

        val readOnly = mvc.page(page, alice)
        operations.forEach { assertFalse(it in readOnly, it) }
        catalog.rule(search, Action.MANAGE, "user:alice")
        catalog.rule(search, Action.GRANT, "user:alice")
        val admin = mvc.page(page, alice)
        operations.forEach { assertContains(admin, it) }
    }

    /**
     * The pages call the same services as the API, so this test is the one test of an anonymous caller on a page.
     * LocalSignInIntegrationTest checks that the sign-in returns the caller to the page.
     */
    @Test
    fun `an anonymous caller goes to sign-in for a page that needs a signed-in caller`() {
        val top = catalog.namespace()

        mvc.get("/namespaces/${top.toApiId()}").andExpect { redirectedUrl("/sign-in") }
        mvc
            .get("/namespaces/${NamespaceId(UUID.randomUUID()).toApiId()}")
            .andExpectProblemPage(404, "NAMESPACE_NOT_FOUND")
    }

    @Test
    fun `an anonymous form post fails the CSRF check first`() {
        mvc.post("/namespaces") { param("slug", "anonymous") }.andExpectProblemPage(403, "CSRF_TOKEN_INVALID")
    }

    @Test
    fun `a signed-in caller without READ gets an error page with the code`() {
        val top = catalog.namespace()

        mvc
            .get("/namespaces/${top.toApiId()}") { with(signedIn(alice)) }
            .andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
    }

    @Test
    fun `a ConfigSet page shows its ID, path and description, and a path opens it`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a", "Search service")
        catalog.rule(top, Action.READ, "public")

        val page = mvc.page("/configsets/${serviceA.toApiId()}", null)

        assertContains(page, "<code>${serviceA.toApiId()}</code>")
        assertContains(page, "<code>${catalog.pathOf(top)}/service-a</code>")
        assertContains(page, "Search service")
        assertContains(page, "GIT")
        mvc.get("/configsets?path=${catalog.pathOf(top)}/service-a").andExpect {
            redirectedUrl("/configsets/${serviceA.toApiId()}")
        }
    }

    @Test
    fun `a form creates a Namespace and shows a notice`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "user:alice")
        catalog.rule(top, Action.READ, "user:alice")

        assertContains(
            mvc.page("/namespaces/new?parentId=${top.toApiId()}", alice),
            """<input type="hidden" name="_csrf" value=""",
        )
        val created =
            mvc
                .postForm("/namespaces", alice, "parentId" to top.toApiId(), "slug" to "search", "description" to "")
                .andExpect { status { is3xxRedirection() } }
                .andReturn()
        val location = assertNotNull(created.response.redirectedUrl)
        assertEquals("Namespace ${catalog.pathOf(top)}/search created.", created.flashMap["notice"])
        mvc.page(location, alice)
    }

    @Test
    fun `a form needs the CSRF token`() {
        mvc
            .post("/namespaces") {
                with(signedIn(SUPER_ADMIN))
                param("slug", "no-token")
            }.andExpectProblemPage(403, "CSRF_TOKEN_INVALID")
    }

    @Test
    fun `a failed form shows the form again with the code, the detail and the status of the API`() {
        val top = catalog.namespace()
        catalog.namespace(top, "taken")

        val page =
            post("/namespaces", "parentId" to top.toApiId(), "slug" to "taken")
                .andExpectProblemPage(409, "SLUG_TAKEN")
                .andReturn()
                .response.contentAsString

        assertContains(page, "is already in use")
        assertContains(page, """value="taken"""")
    }

    /** Only a super admin can create a top-level Namespace, so the create form has the same check as the create. */
    @Test
    fun `the top-level create form and its post need a super admin`() {
        mvc.get("/namespaces/new") { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        mvc.postForm("/namespaces", alice, "slug" to "top-level").andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        mvc.page("/namespaces/new", SUPER_ADMIN)
    }

    @Test
    fun `forms rename, move and delete`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val serviceA = catalog.configSet(from, "service-a")
        val page = "/configsets/${serviceA.toApiId()}"

        post("$page/edit", "slug" to "service-b", "description" to "Renamed").andExpect { redirectedUrl(page) }
        post("$page/move", "target" to to.toApiId()).andExpect { redirectedUrl(page) }
        val moved = mvc.page(page, SUPER_ADMIN)
        assertContains(moved, "${catalog.pathOf(top)}/to/service-b")
        assertContains(moved, "Renamed")
        post("/namespaces/${from.toApiId()}/move", "target" to "").andExpect {
            redirectedUrl("/namespaces/${from.toApiId()}")
        }
        post("$page/delete").andExpect { redirectedUrl("/namespaces/${to.toApiId()}") }
        mvc.page(page, SUPER_ADMIN, 404)
        post("/namespaces/${to.toApiId()}/delete").andExpect { redirectedUrl("/namespaces/${top.toApiId()}") }
    }

    @Test
    fun `a caller with only MANAGE opens the edit form and deletes an empty Namespace`() {
        val top = catalog.namespace()
        val empty = catalog.namespace(top, "empty")
        catalog.rule(top, Action.MANAGE, "user:alice")
        val page = "/namespaces/${empty.toApiId()}"

        assertContains(mvc.page("$page/edit", alice), """value="empty""")
        mvc.page("/namespaces/new?parentId=${top.toApiId()}", alice)
        // Alice has no READ on the parent, so the parent page would give 403. The delete therefore redirects to the
        // start page.
        mvc.postForm("$page/delete", alice).andExpect { redirectedUrl("/") }
    }

    @Test
    fun `a caller that cannot READ the parent sees it as plain text, and a delete goes to the start page`() {
        val dana = Caller.Authenticated(UUID.randomUUID().toString(), emptySet(), null)
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a")
        catalog.rule(serviceA, Action.READ, "user:${dana.subject}")
        catalog.rule(serviceA, Action.MANAGE, "user:${dana.subject}")
        val page = "/configsets/${serviceA.toApiId()}"

        assertFalse(top.toApiId() in mvc.page(page, dana))
        mvc.postForm("$page/delete", dana).andExpect { redirectedUrl("/") }
    }

    /**
     * Some of these pages, such as the delete confirmation, have no other test. [HtmlCheckConfiguration] examines the
     * HTML of each page.
     */
    @Test
    fun `each catalog page renders`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top)
        listOf(
            "/",
            "/namespaces/${top.toApiId()}",
            "/namespaces/new?parentId=${top.toApiId()}",
            "/namespaces/${top.toApiId()}/edit",
            "/namespaces/${top.toApiId()}/move",
            "/namespaces/${top.toApiId()}/delete",
            "/namespaces/${top.toApiId()}/rules?action=READ",
            "/configsets/${serviceA.toApiId()}",
            "/configsets/${serviceA.toApiId()}/rules",
        ).forEach { mvc.page(it, SUPER_ADMIN) }
    }

    private fun post(url: String, vararg params: Pair<String, String>) = mvc.postForm(url, SUPER_ADMIN, *params)
}
