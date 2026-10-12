package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.uniqueSlug
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.*
import java.util.UUID
import kotlin.test.*

@FolioIntegrationTest
class NamespaceMoveIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `a move needs MANAGE on the Namespace and on the new parent`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val moved = catalog.namespace(from, "moved")
        catalog.rule(from, Action.MANAGE, "user:alice")

        move(alice, moved, to).andExpectForbidden(Action.MANAGE, to.toApiId())
        catalog.rule(to, Action.MANAGE, "user:alice")
        move(alice, moved, to).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(moved.toApiId()) }
            jsonPath("$.parentId") { value(to.toApiId()) }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/to/moved") }
        }
    }

    @Test
    fun `a move to the top level needs a super admin`() {
        val top = catalog.namespace()
        val child = catalog.namespace(top, uniqueSlug())
        catalog.rule(top, Action.MANAGE, "user:alice")

        move(alice, child, null).andExpectForbidden(Action.MANAGE, null)
        move(SUPER_ADMIN, child, null).andExpect {
            status { isOk() }
            jsonPath("$.parentId") { value(null) }
            jsonPath("$.path") { value(catalog.pathOf(child)) }
        }
    }

    @Test
    fun `a Namespace cannot move into itself or below itself`() {
        val top = catalog.namespace()
        val a = catalog.namespace(top, "a")
        val b = catalog.namespace(a, "b")

        move(SUPER_ADMIN, a, a).andExpectProblem(409, "MOVE_INTO_OWN_SUBTREE")
        move(SUPER_ADMIN, a, b).andExpectProblem(409, "MOVE_INTO_OWN_SUBTREE")
        move(SUPER_ADMIN, top, b).andExpectProblem(409, "MOVE_INTO_OWN_SUBTREE")
    }

    @Test
    fun `a move of rules needs GRANT on the new parent`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val withRules = catalog.namespace(from, "with-rules")
        val ruleBelow = catalog.namespace(from, "rule-below")
        catalog.rule(catalog.configSet(catalog.namespace(ruleBelow, "deep"), "cfg"), Action.READ, "public")
        val withoutRules = catalog.namespace(from, "without-rules")
        catalog.configSet(withoutRules, "cfg")
        catalog.rule(withRules, Action.READ, "public")
        catalog.rule(top, Action.MANAGE, "user:alice")

        move(alice, withRules, to).andExpectForbidden(Action.GRANT, to.toApiId())
        move(alice, ruleBelow, to).andExpectProblem(403, "ACTION_NOT_ALLOWED")
        move(alice, withoutRules, to).andExpect { status { isOk() } }
        catalog.rule(to, Action.GRANT, "user:alice")
        move(alice, withRules, to).andExpect { status { isOk() } }
        mvc.send(GET, "${url(withRules)}/rules", SUPER_ADMIN).andExpect {
            jsonPath("$.rules[*].action") { value(contains("READ")) }
            jsonPath("$.rules[0].subjects") { value(contains("public")) }
        }
    }

    @Test
    fun `after a move, the resources below inherit from the new parent`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val moved = catalog.namespace(from, "moved")
        val serviceA = catalog.configSet(moved, "service-a")
        catalog.rule(from, Action.READ, "user:alice")
        val configSet = "/api/v1/admin/configsets/${serviceA.toApiId()}"
        mvc.send(GET, configSet, alice).andExpect { status { isOk() } }

        move(SUPER_ADMIN, moved, to).andExpect { status { isOk() } }

        mvc.send(GET, url(moved), alice).andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.send(GET, configSet, alice).andExpectProblem(403, "ACTION_NOT_ALLOWED")
        catalog.rule(to, Action.READ, "user:alice")
        mvc.send(GET, url(moved), alice).andExpect { status { isOk() } }
        mvc.send(GET, configSet, alice).andExpect {
            status { isOk() }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/to/moved/service-a") }
        }
    }

    @Test
    fun `Folio finds the resource, then authorizes, then examines the tree`() {
        val top = catalog.namespace()
        val a = catalog.namespace(top, "a")
        val b = catalog.namespace(a, "b")
        catalog.namespace(top, "taken")
        catalog.rule(top, Action.READ, "user:alice")

        move(alice, a, b).andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.send(DELETE, url(a), alice).andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.send(PATCH, url(a), alice, """{"slug": "taken"}""").andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc
            .send(PATCH, url(NamespaceId(UUID.randomUUID())), SUPER_ADMIN, """{"slug": "Bad"}""")
            .andExpectProblem(400, "INVALID_SLUG")
    }

    @Test
    fun `a move into a parent with the same slug is a conflict`() {
        val top = catalog.namespace()
        val to = catalog.namespace(top, "to")
        catalog.namespace(to, "same")
        val same = catalog.namespace(top, "same")

        move(SUPER_ADMIN, same, to).andExpectProblem(409, "SLUG_TAKEN")
    }

    @Test
    fun `Folio deletes only an empty Namespace`() {
        val top = catalog.namespace()
        val withChild = catalog.namespace(top, "with-child")
        catalog.namespace(withChild, "child")
        val withConfigSet = catalog.namespace(top, "with-config-set")
        catalog.configSet(withConfigSet, "cfg")
        val empty = catalog.namespace(top, "empty")
        // A rule does not make a Namespace non-empty. The delete removes the rule.
        catalog.rule(empty, Action.READ, "public")

        mvc.send(DELETE, url(withChild), SUPER_ADMIN).andExpectProblem(409, "NAMESPACE_NOT_EMPTY")
        mvc.send(DELETE, url(withConfigSet), SUPER_ADMIN).andExpectProblem(409, "NAMESPACE_NOT_EMPTY")
        mvc.send(DELETE, url(empty), SUPER_ADMIN).andExpect { status { isNoContent() } }

        mvc.send(GET, url(empty), SUPER_ADMIN).andExpectProblem(404, "NAMESPACE_NOT_FOUND")
        mvc.send(DELETE, url(empty), SUPER_ADMIN).andExpectProblem(404, "NAMESPACE_NOT_FOUND")
    }

    private fun move(caller: Caller.Authenticated, id: NamespaceId, parent: NamespaceId?): ResultActionsDsl {
        val body = if (parent == null) "{}" else """{"parentId": "${parent.toApiId()}"}"""
        return mvc.send(POST, "${url(id)}:move", caller, body)
    }

    private fun url(id: NamespaceId) = "/api/v1/admin/namespaces/${id.toApiId()}"
}
