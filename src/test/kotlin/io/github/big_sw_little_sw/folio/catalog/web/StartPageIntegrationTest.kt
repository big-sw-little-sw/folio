package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.uniqueSlug
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.*
import kotlin.test.*

/** The start page lists the top-most resources that the caller can read, with their paths. */
@FolioIntegrationTest
class StartPageIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    @Test
    fun `the start page lists the top-level Namespaces that the caller can read`() {
        val open = catalog.namespace()
        val closed = catalog.namespace()
        catalog.rule(open, Action.READ, "public")

        mvc.get("/").andExpect { header { doesNotExist(HttpHeaders.SET_COOKIE) } }
        val anonymous = home(null)
        assertContains(anonymous, """<a href="/namespaces/${open.toApiId()}">${catalog.pathOf(open)}</a>""")
        assertFalse(closed.toApiId() in anonymous)
        assertFalse("Create a top-level Namespace" in anonymous)
        val admin = home(SUPER_ADMIN)
        assertContains(admin, closed.toApiId())
        assertContains(admin, "Create a top-level Namespace")
    }

    @Test
    fun `a deep grant is listed with its path, and only what the caller can read is a link`() {
        val team = uniqueSlug("search-team")
        val dana = Caller.Authenticated("dana", setOf(team), null)
        val engineering = catalog.namespace()
        val search = catalog.namespace(engineering, "search")
        val serviceA = catalog.configSet(search, "service-a")
        catalog.rule(search, Action.READ, "group:$team")
        // The start page does not list a resource below a listed resource again, even when the lower resource has its
        // own rule.
        catalog.rule(serviceA, Action.READ, "group:$team")

        val html = home(dana)

        assertContains(
            html,
            """${catalog.pathOf(engineering)} / <a href="/namespaces/${search.toApiId()}">search</a>""",
        )
        assertFalse(engineering.toApiId() in html, "The caller cannot READ engineering, so it is plain text")
        assertFalse(serviceA.toApiId() in html, "service-a is in the list of search")

        val page = mvc.page("/namespaces/${search.toApiId()}", dana).flat()
        assertContains(page, "/ ${catalog.pathOf(engineering)} / <a href=\"/namespaces/${search.toApiId()}\"")
        assertFalse(engineering.toApiId() in page, "The breadcrumbs follow the same rule as the start page")
    }

    @Test
    fun `a resource below a Namespace that a nearer rule hides is listed with its own path`() {
        val team = uniqueSlug("team")
        val erin = Caller.Authenticated("erin", setOf(team), null)
        val top = catalog.namespace()
        val restricted = catalog.namespace(top, "restricted")
        val leaf = catalog.configSet(restricted, "leaf")
        catalog.rule(top, Action.READ, "group:$team")
        catalog.rule(restricted, Action.READ, "user:somebody-else")
        catalog.rule(leaf, Action.READ, "group:$team")

        val html = home(erin)

        val topLink = """<a href="/namespaces/${top.toApiId()}">${catalog.pathOf(top)}</a>"""
        assertContains(html, topLink)
        assertContains(html, """$topLink / restricted / <a href="/configsets/${leaf.toApiId()}">leaf</a>""")
        assertFalse(restricted.toApiId() in html, "The nearer rule takes READ on restricted away")
    }

    @Test
    fun `an anonymous caller sees public resources`() {
        val closed = catalog.namespace()
        val open = catalog.namespace(closed, "open")
        catalog.rule(open, Action.READ, "public")

        val html = home(null)

        assertContains(html, """${catalog.pathOf(closed)} / <a href="/namespaces/${open.toApiId()}">open</a>""")
        assertFalse(closed.toApiId() in html)
    }

    @Test
    fun `the start page is in path order`() {
        val b = catalog.namespace(slug = uniqueSlug("b"))
        val a = catalog.namespace(slug = uniqueSlug("a"))
        catalog.rule(a, Action.READ, "public")
        catalog.rule(b, Action.READ, "public")

        val html = home(null)

        assertContains(html, a.toApiId())
        assertContains(html, b.toApiId())
        assertTrue(html.indexOf(a.toApiId()) < html.indexOf(b.toApiId()))
    }

    private fun home(caller: Caller.Authenticated?): String = mvc.page("/", caller).flat()

    /**
     * The templates put line breaks and indentation between the parts of a path, so tests replace each run of
     * whitespace with one space.
     */
    private fun String.flat() = replace(Regex("\\s+"), " ")
}
