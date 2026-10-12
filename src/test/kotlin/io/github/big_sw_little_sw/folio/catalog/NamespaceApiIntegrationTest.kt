package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.uniqueSlug
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.*
import java.util.UUID
import kotlin.test.*

@FolioIntegrationTest
class NamespaceApiIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)
    private val bob = Caller.Authenticated("bob", setOf("team-b"), null)

    @Test
    fun `a super admin creates a top-level Namespace`() {
        val slug = uniqueSlug()

        val created = mvc.send(POST, NAMESPACES, SUPER_ADMIN, """{"slug": "$slug", "description": "All teams"}""")
        created.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(startsWith("ns_")) }
            jsonPath("$.parentId") { value(null) }
            jsonPath("$.slug") { value(slug) }
            jsonPath("$.path") { value(slug) }
            jsonPath("$.description") { value("All teams") }
        }
        val id = created.json("$.id")
        created.andExpect { header { string(HttpHeaders.LOCATION, "$NAMESPACES/$id") } }
    }

    @Test
    fun `only super admins create top-level Namespaces`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "user:alice")

        mvc.send(POST, NAMESPACES, alice, """{"slug": "${uniqueSlug()}"}""").andExpectForbidden(Action.MANAGE, null)
    }

    /**
     * One authorization check gives 403 to a caller without the action and 401 to an anonymous caller. Each route uses
     * that check, so the 403 test of a route also covers its 401, and only this test checks the 401.
     */
    @Test
    fun `a child needs MANAGE on the parent, and an anonymous caller must authenticate`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "group:team-a")
        val body = """{"parentId": "${top.toApiId()}", "slug": "search"}"""

        mvc.send(POST, NAMESPACES, alice, body).andExpect {
            status { isCreated() }
            jsonPath("$.parentId") { value(top.toApiId()) }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/search") }
        }
        mvc.send(POST, NAMESPACES, bob, body).andExpectForbidden(Action.MANAGE, top.toApiId())
        mvc.send(POST, NAMESPACES, null, body).andExpectProblem(401, "AUTHENTICATION_REQUIRED").andExpect {
            jsonPath("$.detail") { value(containsString("MANAGE")) }
        }
    }

    @Test
    fun `create rejects requests that are not valid`() {
        val top = catalog.namespace()
        val missing = NamespaceId(UUID.randomUUID()).toApiId()

        listOf(
            """{"slug": "Not-Valid"}""" to "INVALID_SLUG",
            """{"slug": "ok", "parentId": "ns_123"}""" to "INVALID_ID",
            """{"slug": "ok", "parentId": "${top.toApiId()}", "description": "${"x".repeat(1001)}"}""" to
                "INVALID_DESCRIPTION",
            // PostgreSQL rejects a NUL character in text. Folio rejects the description first, so the caller gets 400
            // and not 500.
            """{"slug": "ok", "parentId": "${top.toApiId()}", "description": "x\u0000y"}""" to "INVALID_DESCRIPTION",
            """{"parentId": "${top.toApiId()}"}""" to "INVALID_REQUEST",
        ).forEach { (body, code) ->
            mvc.send(POST, NAMESPACES, SUPER_ADMIN, body).andExpectProblem(400, code)
        }
        mvc
            .send(POST, NAMESPACES, SUPER_ADMIN, """{"slug": "ok", "parentId": "$missing"}""")
            .andExpectProblem(404, "NAMESPACE_NOT_FOUND")
    }

    @Test
    fun `a description has 1,000 characters, also when each character needs two UTF-16 units`() {
        val top = catalog.namespace()
        val emoji = "😀"
        fun body(slug: String, count: Int) =
            """{"parentId": "${top.toApiId()}", "slug": "$slug", "description": "${emoji.repeat(count)}"}"""

        mvc.send(POST, NAMESPACES, SUPER_ADMIN, body("wide", 1000)).andExpect {
            status { isCreated() }
            jsonPath("$.description") { value(emoji.repeat(1000)) }
        }
        mvc.send(POST, NAMESPACES, SUPER_ADMIN, body("wider", 1001)).andExpectProblem(400, "INVALID_DESCRIPTION")
    }

    @Test
    fun `sibling slugs are unique, also at the top level`() {
        val top = catalog.namespace()
        catalog.namespace(top, "search")

        mvc
            .send(POST, NAMESPACES, SUPER_ADMIN, """{"parentId": "${top.toApiId()}", "slug": "search"}""")
            .andExpectProblem(409, "SLUG_TAKEN")
        mvc
            .send(POST, NAMESPACES, SUPER_ADMIN, """{"slug": "${catalog.pathOf(top)}"}""")
            .andExpectProblem(409, "SLUG_TAKEN")
    }

    @Test
    fun `get shows the Namespace and only the children that the caller can READ`() {
        val top = catalog.namespace(description = "Top")
        val open = catalog.namespace(top, "open")
        val closed = catalog.namespace(top, "closed")
        val visible = catalog.configSet(top, "visible")
        val hidden = catalog.configSet(top, "hidden")
        catalog.rule(top, Action.READ, "user:alice")
        catalog.rule(closed, Action.READ, "user:bob")
        catalog.rule(hidden, Action.READ, "user:bob")

        mvc.send(GET, url(top), alice).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(top.toApiId()) }
            jsonPath("$.path") { value(catalog.pathOf(top)) }
            jsonPath("$.description") { value("Top") }
            jsonPath("$.namespaces[*].id") { value(contains(open.toApiId())) }
            jsonPath("$.namespaces[0].path") { value("${catalog.pathOf(top)}/open") }
            jsonPath("$.configSets[*].id") { value(contains(visible.toApiId())) }
            jsonPath("$.configSets[0].sourceType") { value("GIT") }
        }
    }

    @Test
    fun `the list shows only the top-level Namespaces that the caller can READ`() {
        val carol = Caller.Authenticated(uniqueSlug("carol"), emptySet(), null)
        val readable = catalog.namespace()
        val open = catalog.namespace()
        val hidden = catalog.namespace()
        val deep = catalog.namespace(hidden, "deep")
        catalog.rule(readable, Action.READ, "user:${carol.subject}")
        catalog.rule(open, Action.READ, "public")
        catalog.rule(deep, Action.READ, "user:${carol.subject}")
        val ids = "$.namespaces[*].id"

        mvc.send(GET, NAMESPACES, carol).andExpect {
            status { isOk() }
            jsonPath(ids) { value(hasItems(readable.toApiId(), open.toApiId())) }
            jsonPath(ids) { value(not(hasItem(hidden.toApiId()))) }
            jsonPath(ids) { value(not(hasItem(deep.toApiId()))) }
            jsonPath("$.namespaces[?(@.id == '${readable.toApiId()}')].path") {
                value(contains(catalog.pathOf(readable)))
            }
        }
    }

    @Test
    fun `the list shows public Namespaces to anonymous callers and all to super admins, but not to a bad token`() {
        // The test creates the Namespaces in the opposite order of their slugs. The list order then shows that the list
        // uses the slug order.
        val open = catalog.namespace(slug = uniqueSlug("b"))
        val closed = catalog.namespace(slug = uniqueSlug("a"))
        catalog.rule(open, Action.READ, "public")
        val ids = "$.namespaces[*].id"

        mvc.send(GET, NAMESPACES, null).andExpect {
            status { isOk() }
            jsonPath(ids) { value(hasItem(open.toApiId())) }
            jsonPath(ids) { value(not(hasItem(closed.toApiId()))) }
        }
        val all = mvc.send(GET, NAMESPACES, SUPER_ADMIN).json(ids)
        assertTrue(all.indexOf(closed.toApiId()) in 0..<all.indexOf(open.toApiId()), "The list is in slug order")
        mvc.get(NAMESPACES) { bearer("not-a-token") }.andExpectProblem(401, "INVALID_TOKEN")
    }

    @Test
    fun `get finds the Namespace first, then authorizes`() {
        val top = catalog.namespace()
        val missing = NamespaceId(UUID.randomUUID()).toApiId()

        mvc.send(GET, "$NAMESPACES/$missing", alice).andExpectProblem(404, "NAMESPACE_NOT_FOUND")
        mvc.send(GET, "$NAMESPACES/$missing", null).andExpectProblem(404, "NAMESPACE_NOT_FOUND")
        mvc.send(GET, url(top), alice).andExpectForbidden(Action.READ, top.toApiId())
        mvc
            .send(GET, "$NAMESPACES/cfg_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e", alice)
            .andExpectProblem(400, "INVALID_ID")
    }

    @Test
    fun `a caller that can READ a deep Namespace sees its path`() {
        val top = catalog.namespace()
        val deep = catalog.namespace(catalog.namespace(top, "a"), "b")
        catalog.rule(deep, Action.READ, "user:alice")

        mvc.send(GET, url(deep), alice).andExpect {
            status { isOk() }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/a/b") }
        }
    }

    @Test
    fun `a rename keeps the ID and frees the old slug immediately`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search", "Search team")
        catalog.rule(top, Action.MANAGE, "user:alice")

        mvc.send(PATCH, url(search), alice, """{"slug": "find"}""").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(search.toApiId()) }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/find") }
            jsonPath("$.description") { value("Search team") }
        }
        catalog.namespace(top, "search")
    }

    @Test
    fun `a rename changes the paths of the resources below, and their old paths stop resolving`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search")
        val serviceA = catalog.configSet(search, "service-a")
        catalog.rule(top, Action.READ, "public")

        mvc.send(PATCH, url(search), SUPER_ADMIN, """{"slug": "find"}""").andExpect { status { isOk() } }

        val topPath = catalog.pathOf(top)
        mvc.send(GET, "$RESOLVE$topPath/search/service-a", alice).andExpectProblem(404, "PATH_NOT_FOUND")
        mvc.send(GET, "$RESOLVE$topPath/find/service-a", alice).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(serviceA.toApiId()) }
        }
        mvc.send(GET, "/api/v1/admin/configsets/${serviceA.toApiId()}", alice).andExpect {
            jsonPath("$.path") { value("$topPath/find/service-a") }
        }
    }

    @Test
    fun `a describe sets or clears the description, and an absent field stays the same`() {
        val top = catalog.namespace(description = "Old")

        mvc.send(PATCH, url(top), SUPER_ADMIN, """{"description": "New"}""").andExpect {
            jsonPath("$.description") { value("New") }
        }
        mvc.send(PATCH, url(top), SUPER_ADMIN, """{"slug": "${catalog.pathOf(top)}"}""").andExpect {
            jsonPath("$.description") { value("New") }
        }
        mvc.send(PATCH, url(top), SUPER_ADMIN, """{"description": null}""").andExpect {
            jsonPath("$.description") { value(null) }
        }
    }

    @Test
    fun `a rename to the slug of a sibling is a conflict`() {
        val top = catalog.namespace()
        catalog.namespace(top, "taken")
        val other = catalog.namespace(top, "other")

        mvc.send(PATCH, url(other), SUPER_ADMIN, """{"slug": "taken"}""").andExpectProblem(409, "SLUG_TAKEN")
    }

    private fun url(id: NamespaceId) = "$NAMESPACES/${id.toApiId()}"

    private companion object {
        const val NAMESPACES = "/api/v1/admin/namespaces"
        const val RESOLVE = "/api/v1/configsets:resolve?path="
    }
}
