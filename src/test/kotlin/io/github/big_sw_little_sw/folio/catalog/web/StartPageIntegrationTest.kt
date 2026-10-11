package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.signedIn
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests check the start page (design 7.4, 12.3). The start page lists the top-most resources that the caller can
 * read, with their paths. Test classes share the database, so each test names its own subjects and examines only its
 * own resources.
 */
@FolioIntegrationTest
class StartPageIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    @Test
    fun `the start page lists the top-level Namespaces that the caller can read`() {
        val open = catalog.namespace()
        val closed = catalog.namespace()
        catalog.rule(open, Action.READ, "public")

        mvc.get("/").andExpect {
            status { isOk() }
            content {
                string(
                    allOf(
                        containsString("""<a href="/namespaces/${open.toApiId()}">${catalog.pathOf(open)}</a>"""),
                        not(containsString(closed.toApiId())),
                        not(containsString("Create a top-level Namespace")),
                    ),
                )
            }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }
        mvc.get("/") { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(allOf(containsString(closed.toApiId()), containsString("Create a top-level Namespace"))) }
        }
    }

    @Test
    fun `a deep grant is listed with its path, and only what the caller can read is a link`() {
        val team = TestCatalog.uniqueSlug("search-team")
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

        val page = page(dana, "/namespaces/${search.toApiId()}")
        assertContains(page, "/ ${catalog.pathOf(engineering)} / <a href=\"/namespaces/${search.toApiId()}\"")
        assertFalse(engineering.toApiId() in page, "The breadcrumbs follow the same rule as the start page")
    }

    @Test
    fun `a resource below a Namespace that a nearer rule hides is listed with its own path`() {
        val team = TestCatalog.uniqueSlug("team")
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

        val html = anonymousHome()

        assertContains(html, """${catalog.pathOf(closed)} / <a href="/namespaces/${open.toApiId()}">open</a>""")
        assertFalse(closed.toApiId() in html)
    }

    @Test
    fun `the start page is in path order`() {
        val b = catalog.namespace(slug = TestCatalog.uniqueSlug("b"))
        val a = catalog.namespace(slug = TestCatalog.uniqueSlug("a"))
        catalog.rule(a, Action.READ, "public")
        catalog.rule(b, Action.READ, "public")

        val html = anonymousHome()

        assertContains(html, a.toApiId())
        assertContains(html, b.toApiId())
        assertTrue(html.indexOf(a.toApiId()) < html.indexOf(b.toApiId()))
    }

    private fun home(caller: Caller.Authenticated): String = page(caller, "/")

    private fun anonymousHome(): String = mvc
        .get("/")
        .andExpect { status { isOk() } }
        .andReturn()
        .response.contentAsString
        .flat()

    private fun page(caller: Caller.Authenticated, url: String): String = mvc
        .get(url) { with(signedIn(caller)) }
        .andExpect { status { isOk() } }
        .andReturn()
        .response.contentAsString
        .flat()

    /**
     * The templates put line breaks and indentation between the parts of a path, so tests replace each run of
     * whitespace with one space.
     */
    private fun String.flat() = replace(Regex("\\s+"), " ")
}
