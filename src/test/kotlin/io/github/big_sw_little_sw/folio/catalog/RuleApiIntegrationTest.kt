package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.andExpectForbidden
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.json
import io.github.big_sw_little_sw.testing.jsonBody
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.Test

/** These tests check the rule routes and the explain route (design 7.2, 7.4, 12.2). All of these routes need GRANT. */
@FolioIntegrationTest
class RuleApiIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)
    private val bob = Caller.Authenticated("bob", setOf("team-b"), "search-app")

    @Test
    fun `a caller with GRANT puts, lists and deletes rules`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.GRANT, "user:alice")
        val rules = "/api/v1/admin/namespaces/${top.toApiId()}/rules"

        mvc
            .put("$rules/READ") {
                bearer(alice)
                jsonBody("""{"subjects": ["group:team-b", "application:search-app"]}""")
            }.andExpect {
                status { isOk() }
                jsonPath("$.action") { value("READ") }
                jsonPath("$.subjects") { value(contains("group:team-b", "application:search-app")) }
            }
        mvc
            .put("$rules/READ") {
                bearer(alice)
                jsonBody("""{"subjects": ["public"]}""")
            }.andExpect { jsonPath("$.subjects") { value(contains("public")) } }
        mvc.get(rules) { bearer(alice) }.andExpect {
            status { isOk() }
            jsonPath("$.rules[*].action") { value(contains("READ", "GRANT")) }
            jsonPath("$.rules[0].subjects") { value(contains("public")) }
        }
        mvc.delete("$rules/READ") { bearer(alice) }.andExpect { status { isNoContent() } }
        mvc.get(rules) { bearer(alice) }.andExpect { jsonPath("$.rules[*].action") { value(contains("GRANT")) } }
    }

    @Test
    fun `a delete of a rule that does not exist succeeds`() {
        val serviceA = catalog.configSet(catalog.namespace())

        mvc
            .delete("/api/v1/admin/configsets/${serviceA.toApiId()}/rules/MANAGE") { bearer(SUPER_ADMIN) }
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `rules need GRANT on the resource, which can come from a Namespace above`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top)
        catalog.rule(top, Action.GRANT, "group:team-a")
        catalog.rule(top, Action.MANAGE, "user:bob")
        val rules = "/api/v1/admin/configsets/${serviceA.toApiId()}/rules"

        mvc.get(rules) { bearer(alice) }.andExpect { status { isOk() } }
        mvc.get(rules) { bearer(bob) }.andExpectForbidden(Action.GRANT, serviceA.toApiId())
        mvc.get(rules).andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        mvc
            .put("$rules/READ") {
                bearer(bob)
                jsonBody("""{"subjects": ["public"]}""")
            }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.delete("$rules/READ") { bearer(bob) }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
    }

    @Test
    fun `rule routes find the resource first`() {
        val missing = ConfigSetId(UUID.randomUUID()).toApiId()

        mvc
            .get(
                "/api/v1/admin/configsets/$missing/rules",
            ) { bearer(alice) }
            .andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
        mvc
            .get("/api/v1/admin/namespaces/$missing/rules") { bearer(alice) }
            .andExpectProblem(400, "INVALID_ID")
    }

    @Test
    fun `a rule has one or more valid subjects and a known action`() {
        val rules = "/api/v1/admin/namespaces/${catalog.namespace().toApiId()}/rules"

        listOf(
            "READ" to """{"subjects": []}""" to "NO_SUBJECTS",
            "READ" to """{"subjects": ["everyone"]}""" to "INVALID_SUBJECT",
            "READ" to """{"subjects": ["user:"]}""" to "INVALID_SUBJECT",
            "read" to """{"subjects": ["public"]}""" to "INVALID_REQUEST",
            "WRITE" to """{"subjects": ["public"]}""" to "INVALID_REQUEST",
            "READ" to """{}""" to "INVALID_REQUEST",
        ).forEach { (request, code) ->
            mvc
                .put("$rules/${request.first}") {
                    bearer(SUPER_ADMIN)
                    jsonBody(request.second)
                }.andExpectProblem(400, code)
        }
    }

    @Test
    fun `the nearest rule decides`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search")
        val serviceA = catalog.configSet(search, "service-a")
        catalog.rule(top, Action.READ, "group:team-a", "group:team-b")
        val get = "/api/v1/admin/configsets/${serviceA.toApiId()}"

        mvc.get(get) { bearer(alice) }.andExpect { status { isOk() } }
        catalog.rule(serviceA, Action.READ, "application:search-app")
        mvc.get(get) { bearer(alice) }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.get(get) { bearer(bob) }.andExpect { status { isOk() } }
    }

    @Test
    fun `an explanation names the rule that decided, its resource and the matched subject`() {
        val (search, serviceA) = tree()
        val path = mvc.get("/api/v1/admin/namespaces/${search.toApiId()}") { bearer(SUPER_ADMIN) }.json("$.path")

        explain(serviceA, """{"action": "READ", "caller": {"subject": "alice", "groups": ["team-a"]}}""").andExpect {
            status { isOk() }
            jsonPath("$.action") { value("READ") }
            jsonPath("$.allowed") { value(true) }
            jsonPath("$.reason") { value("GRANTED") }
            jsonPath("$.matchedSubject") { value("group:team-a") }
            jsonPath("$.rule.resourceId") { value(search.toApiId()) }
            jsonPath("$.rule.path") { value(path) }
        }
    }

    @Test
    fun `an explanation does not show the subjects of a rule above the resource`() {
        val (search, serviceA) = tree()
        catalog.rule(serviceA, Action.GRANT, "user:alice")

        mvc
            .post("/api/v1/admin/configsets/${serviceA.toApiId()}/explain") {
                bearer(alice)
                jsonBody("""{"action": "READ", "caller": {"subject": "bob"}}""")
            }.andExpect {
                status { isOk() }
                jsonPath("$.reason") { value("GRANTED") }
                jsonPath("$.matchedSubject") { value("user:bob") }
                jsonPath("$.rule.resourceId") { value(search.toApiId()) }
                jsonPath("$.rule.subjects") { doesNotExist() }
                content { string(not(containsString("group:team-a"))) }
            }
    }

    @Test
    fun `an explanation matches the application ID and public`() {
        val (_, serviceA) = tree()
        catalog.rule(serviceA, Action.READ, "application:search-app", "public")

        explain(
            serviceA,
            """{"action": "READ", "caller": {"subject": "x", "applicationId": "search-app"}}""",
        ).andExpect {
            jsonPath("$.allowed") { value(true) }
            jsonPath("$.reason") { value("GRANTED") }
            jsonPath("$.matchedSubject") { value("application:search-app") }
            jsonPath("$.rule.resourceId") { value(serviceA.toApiId()) }
        }
        explain(serviceA, """{"action": "READ"}""").andExpect {
            jsonPath("$.allowed") { value(true) }
            jsonPath("$.reason") { value("GRANTED") }
            jsonPath("$.matchedSubject") { value("public") }
        }
    }

    @Test
    fun `an explanation shows the nearest rule also when none of its subjects matches`() {
        val (search, serviceA) = tree()

        explain(serviceA, """{"action": "READ", "caller": {"subject": "carol"}}""").andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("NOT_GRANTED") }
            jsonPath("$.matchedSubject") { value(null) }
            jsonPath("$.rule.resourceId") { value(search.toApiId()) }
        }
        explain(serviceA, """{"action": "READ"}""").andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("NOT_GRANTED") }
        }
    }

    @Test
    fun `an explanation shows that no rule exists or that a super-admin entry matched`() {
        val (_, serviceA) = tree()

        explain(serviceA, """{"action": "MANAGE", "caller": {"subject": "alice", "groups": ["team-a"]}}""").andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("NO_RULE") }
            jsonPath("$.rule") { value(null) }
        }
        explain(
            serviceA,
            """{"action": "MANAGE", "caller": {"subject": "x", "groups": ["folio-admins"]}}""",
        ).andExpect {
            jsonPath("$.allowed") { value(true) }
            jsonPath("$.reason") { value("SUPER_ADMIN") }
            jsonPath("$.matchedSubject") { value("group:folio-admins") }
        }
    }

    @Test
    fun `an explanation needs GRANT`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.READ, "user:alice")
        val url = "/api/v1/admin/namespaces/${top.toApiId()}/explain"
        val body = """{"action": "READ"}"""

        mvc
            .post(url) {
                bearer(alice)
                jsonBody(body)
            }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.post(url) { jsonBody(body) }.andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        mvc
            .post(url) {
                bearer(SUPER_ADMIN)
                jsonBody("""{"action": "READ", "caller": {"groups": ["a"]}}""")
            }.andExpectProblem(400, "INVALID_REQUEST")
    }

    /**
     * Creates a Namespace `search` with a READ rule below a new top-level Namespace. The Namespace `search` contains
     * the ConfigSet `service-a`.
     */
    private fun tree(): Pair<NamespaceId, ConfigSetId> {
        val search = catalog.namespace(catalog.namespace(), "search")
        catalog.rule(search, Action.READ, "user:bob", "group:team-a")
        return search to catalog.configSet(search, "service-a")
    }

    private fun explain(resource: ConfigSetId, body: String) =
        mvc.post("/api/v1/admin/configsets/${resource.toApiId()}/explain") {
            bearer(SUPER_ADMIN)
            jsonBody(body)
        }
}
