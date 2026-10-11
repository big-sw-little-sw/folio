package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.uniqueSlug
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.jsonBody
import org.hamcrest.Matchers.contains
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Moves and deletes of Namespaces (design 5.4, 7.3), through HTTP with bearer tokens. */
@FolioIntegrationTest
class NamespaceMoveIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val jdbc: JdbcClient,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `a move needs MANAGE on the Namespace and on the new parent`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val moved = catalog.namespace(from, "moved")
        catalog.rule(from, Action.MANAGE, "user:alice")

        move(alice, moved, to).andExpectProblem(403, "ACTION_NOT_ALLOWED").andExpect {
            jsonPath("$.detail") { value("The caller does not have MANAGE on ${to.toApiId()}.") }
        }
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

        move(alice, child, null).andExpectProblem(403, "ACTION_NOT_ALLOWED").andExpect {
            jsonPath("$.detail") { value("The caller does not have MANAGE on the root.") }
        }
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

        move(alice, withRules, to).andExpectProblem(403, "ACTION_NOT_ALLOWED").andExpect {
            jsonPath("$.detail") { value("The caller does not have GRANT on ${to.toApiId()}.") }
        }
        move(alice, ruleBelow, to).andExpectProblem(403, "ACTION_NOT_ALLOWED")
        move(alice, withoutRules, to).andExpect { status { isOk() } }
        catalog.rule(to, Action.GRANT, "user:alice")
        move(alice, withRules, to).andExpect { status { isOk() } }
        mvc.get("$NAMESPACES/${withRules.toApiId()}/rules") { bearer(SUPER_ADMIN) }.andExpect {
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
        val namespace = "$NAMESPACES/${moved.toApiId()}"
        val configSet = "/api/v1/admin/configsets/${serviceA.toApiId()}"
        mvc.get(configSet) { bearer(alice) }.andExpect { status { isOk() } }

        move(SUPER_ADMIN, moved, to).andExpect { status { isOk() } }

        mvc.get(namespace) { bearer(alice) }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.get(configSet) { bearer(alice) }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        catalog.rule(to, Action.READ, "user:alice")
        mvc.get(namespace) { bearer(alice) }.andExpect { status { isOk() } }
        mvc.get(configSet) { bearer(alice) }.andExpect {
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
        mvc
            .post("$NAMESPACES/${a.toApiId()}:move") {
                jsonBody("""{"parentId": "${b.toApiId()}"}""")
            }.andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        mvc.delete("$NAMESPACES/${a.toApiId()}") { bearer(alice) }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.delete("$NAMESPACES/${a.toApiId()}").andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        mvc
            .patch("$NAMESPACES/${a.toApiId()}") {
                bearer(alice)
                jsonBody("""{"slug": "taken"}""")
            }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc
            .patch("$NAMESPACES/${NamespaceId(UUID.randomUUID()).toApiId()}") {
                bearer(SUPER_ADMIN)
                jsonBody("""{"slug": "Bad"}""")
            }.andExpectProblem(400, "INVALID_SLUG")
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
        catalog.rule(empty, Action.READ, "public")

        mvc
            .delete(
                "$NAMESPACES/${withChild.toApiId()}",
            ) { bearer(SUPER_ADMIN) }
            .andExpectProblem(409, "NAMESPACE_NOT_EMPTY")
        mvc
            .delete("$NAMESPACES/${withConfigSet.toApiId()}") { bearer(SUPER_ADMIN) }
            .andExpectProblem(409, "NAMESPACE_NOT_EMPTY")
        mvc.delete("$NAMESPACES/${empty.toApiId()}") { bearer(alice) }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.delete("$NAMESPACES/${empty.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect { status { isNoContent() } }

        mvc.get("$NAMESPACES/${empty.toApiId()}") { bearer(SUPER_ADMIN) }.andExpectProblem(404, "NAMESPACE_NOT_FOUND")
        assertEquals(0, rulesOf(empty), "The rules go with the Namespace")
        mvc
            .delete(
                "$NAMESPACES/${empty.toApiId()}",
            ) { bearer(SUPER_ADMIN) }
            .andExpectProblem(404, "NAMESPACE_NOT_FOUND")
    }

    private fun move(caller: Caller.Authenticated, id: NamespaceId, parent: NamespaceId?) =
        mvc.post("$NAMESPACES/${id.toApiId()}:move") {
            bearer(caller)
            jsonBody(if (parent == null) "{}" else """{"parentId": "${parent.toApiId()}"}""")
        }

    private fun rulesOf(id: NamespaceId): Int = jdbc
        .sql("select count(*) from rule where namespace_id = :id")
        .param("id", id.value)
        .query(Int::class.java)
        .single()

    private companion object {
        const val NAMESPACES = "/api/v1/admin/namespaces"
    }
}
