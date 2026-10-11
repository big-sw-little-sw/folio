package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.jsonBody
import io.github.big_sw_little_sw.testing.postForm
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * These tests check the audit records of the catalog (design 14). Test classes share the database, so each test reads
 * only the records of its own resources.
 */
@FolioIntegrationTest
class CatalogAuditIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val configSets: ConfigSets,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mapper: JsonMapper,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), "cli")

    @Test
    fun `a create records the path, the actor and the super-admin flag`() {
        val top = catalog.namespace(description = "Top")
        val slug = catalog.pathOf(top)

        val record = records(top.value).single()
        assertEquals("NAMESPACE_CREATED", record["operation"])
        assertEquals("NAMESPACE", record["resource_type"])
        assertEquals(slug, record["path"])
        assertEquals(null, record["previous_path"])
        assertEquals("root", record["actor_subject"])
        assertEquals(true, record["actor_super_admin"])
        assertEquals(json("""{"parentId": null, "description": "Top"}"""), json(record["details"]))
    }

    @Test
    fun `identity sets the super-admin flag of a record from the bearer token`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "user:alice")

        patch("/api/v1/admin/namespaces/${top.toApiId()}", """{"description": "By root"}""")
        mvc
            .patch("/api/v1/admin/namespaces/${top.toApiId()}") {
                bearer(alice)
                jsonBody("""{"description": "By alice"}""")
            }.andExpect { status { isOk() } }

        val records = records(top.value).filter { it["operation"] == "NAMESPACE_DESCRIBED" }
        assertEquals(listOf("root", "alice"), records.map { it["actor_subject"] })
        assertEquals(listOf(true, false), records.map { it["actor_super_admin"] })
    }

    @Test
    fun `a ConfigSet create by a caller with MANAGE records no super-admin flag`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "user:alice")

        val created = configSets.create(alice, top, Slug("service-a"), null, SourceType.GIT)

        val record = records(created.id.value).single()
        assertEquals("CONFIG_SET_CREATED", record["operation"])
        assertEquals("CONFIG_SET", record["resource_type"])
        assertEquals("${catalog.pathOf(top)}/service-a", record["path"])
        assertEquals("cli", record["actor_application_id"])
        assertEquals(false, record["actor_super_admin"])
        assertEquals(
            json("""{"namespaceId": "${top.toApiId()}", "sourceType": "GIT", "description": null}"""),
            json(record["details"]),
        )
    }

    @Test
    fun `a PATCH of the slug and the description writes a rename and a describe`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search", "Old")

        patch("/api/v1/admin/namespaces/${search.toApiId()}", """{"slug": "find", "description": "New"}""")

        val records = records(search.value).drop(1)
        assertEquals(listOf("NAMESPACE_RENAMED", "NAMESPACE_DESCRIBED"), records.map { it["operation"] })
        assertEquals("${catalog.pathOf(top)}/find", records[0]["path"])
        assertEquals("${catalog.pathOf(top)}/search", records[0]["previous_path"])
        assertEquals(json("""{"slug": "find", "previousSlug": "search"}"""), json(records[0]["details"]))
        assertEquals("${catalog.pathOf(top)}/find", records[1]["path"])
        assertEquals(json("""{"description": "New", "previousDescription": "Old"}"""), json(records[1]["details"]))
    }

    @Test
    fun `edits and moves that change nothing write no record`() {
        val top = catalog.namespace(description = "Same")
        val child = catalog.namespace(top, "child")
        val serviceA = catalog.configSet(top, "service-a")

        patch(
            "/api/v1/admin/namespaces/${top.toApiId()}",
            """{"slug": "${catalog.pathOf(top)}", "description": "Same"}""",
        ).andExpect { status { isOk() } }
        patch("/api/v1/admin/configsets/${serviceA.toApiId()}", "{}").andExpect { status { isOk() } }
        mvc
            .post("/api/v1/admin/configsets/${serviceA.toApiId()}:move") {
                bearer(SUPER_ADMIN)
                jsonBody("""{"namespaceId": "${top.toApiId()}"}""")
            }.andExpect { status { isOk() } }
        mvc
            .post("/api/v1/admin/namespaces/${child.toApiId()}:move") {
                bearer(SUPER_ADMIN)
                jsonBody("""{"parentId": "${top.toApiId()}"}""")
            }.andExpect { status { isOk() } }

        assertEquals(listOf("NAMESPACE_CREATED"), records(top.value).map { it["operation"] })
        assertEquals(listOf("NAMESPACE_CREATED"), records(child.value).map { it["operation"] })
        assertEquals(listOf("CONFIG_SET_CREATED"), records(serviceA.value).map { it["operation"] })
    }

    @Test
    fun `rule writes that change nothing write no record`() {
        val serviceA = catalog.configSet(catalog.namespace())
        catalog.rule(serviceA, Action.READ, "public")

        mvc
            .put("/api/v1/admin/configsets/${serviceA.toApiId()}/rules/READ") {
                bearer(SUPER_ADMIN)
                jsonBody("""{"subjects": ["public"]}""")
            }.andExpect { status { isOk() } }
        mvc
            .delete("/api/v1/admin/configsets/${serviceA.toApiId()}/rules/MANAGE") { bearer(SUPER_ADMIN) }
            .andExpect { status { isNoContent() } }

        assertEquals(listOf("CONFIG_SET_CREATED", "RULE_PUT"), records(serviceA.value).map { it["operation"] })
    }

    @Test
    fun `a UI edit with the same description in CRLF form writes only a rename`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search")
        patch("/api/v1/admin/namespaces/${search.toApiId()}", """{"description": "line 1\nline 2"}""")
            .andExpect { status { isOk() } }

        mvc
            .postForm(
                "/namespaces/${search.toApiId()}/edit",
                SUPER_ADMIN,
                "slug" to "find",
                "description" to "line 1\r\nline 2",
            ).andExpect { status { is3xxRedirection() } }

        val records = records(search.value).drop(2)
        assertEquals(listOf("NAMESPACE_RENAMED"), records.map { it["operation"] })
    }

    @Test
    fun `denied requests and conflicts write no record`() {
        val top = catalog.namespace()
        catalog.namespace(top, "taken")
        val other = catalog.namespace(top, "other")

        mvc
            .patch("/api/v1/admin/namespaces/${other.toApiId()}") {
                bearer(alice)
                jsonBody("""{"slug": "free"}""")
            }.andExpectProblem(403, "ACTION_NOT_ALLOWED")
        patch("/api/v1/admin/namespaces/${other.toApiId()}", """{"slug": "taken"}""")
            .andExpectProblem(409, "SLUG_TAKEN")
        mvc
            .delete("/api/v1/admin/namespaces/${top.toApiId()}") { bearer(SUPER_ADMIN) }
            .andExpectProblem(409, "NAMESPACE_NOT_EMPTY")

        assertEquals(listOf("NAMESPACE_CREATED"), records(other.value).map { it["operation"] })
        assertEquals(listOf("NAMESPACE_CREATED"), records(top.value).map { it["operation"] })
    }

    @Test
    fun `a move records the new and the previous path, and a delete the path before the delete`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val serviceA = catalog.configSet(from, "service-a")

        mvc
            .post("/api/v1/admin/configsets/${serviceA.toApiId()}:move") {
                bearer(SUPER_ADMIN)
                jsonBody("""{"namespaceId": "${to.toApiId()}"}""")
            }.andExpect { status { isOk() } }
        mvc.delete("/api/v1/admin/configsets/${serviceA.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            status { isNoContent() }
        }

        val records = records(serviceA.value)
        assertEquals(
            listOf("CONFIG_SET_CREATED", "CONFIG_SET_MOVED", "CONFIG_SET_DELETED"),
            records.map { it["operation"] },
        )
        assertEquals("${catalog.pathOf(top)}/to/service-a", records[1]["path"])
        assertEquals("${catalog.pathOf(top)}/from/service-a", records[1]["previous_path"])
        assertEquals(
            json("""{"namespaceId": "${to.toApiId()}", "previousNamespaceId": "${from.toApiId()}"}"""),
            json(records[1]["details"]),
        )
        assertEquals("${catalog.pathOf(top)}/to/service-a", records[2]["path"])
    }

    @Test
    fun `a Namespace move records both paths and parents, and a delete the path before the delete`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val moved = catalog.namespace(from, "moved")
        val topPath = catalog.pathOf(top)

        mvc
            .post("/api/v1/admin/namespaces/${moved.toApiId()}:move") {
                bearer(SUPER_ADMIN)
                jsonBody("""{"parentId": "${to.toApiId()}"}""")
            }.andExpect { status { isOk() } }
        mvc.delete("/api/v1/admin/namespaces/${moved.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            status { isNoContent() }
        }

        val records = records(moved.value)
        assertEquals(
            listOf("NAMESPACE_CREATED", "NAMESPACE_MOVED", "NAMESPACE_DELETED"),
            records.map { it["operation"] },
        )
        assertEquals("$topPath/to/moved", records[1]["path"])
        assertEquals("$topPath/from/moved", records[1]["previous_path"])
        assertEquals(
            json("""{"parentId": "${to.toApiId()}", "previousParentId": "${from.toApiId()}"}"""),
            json(records[1]["details"]),
        )
        assertEquals("$topPath/to/moved", records[2]["path"])
        assertEquals(null, records[2]["previous_path"])
    }

    @Test
    fun `a ConfigSet PATCH of the slug and the description writes a rename and a describe`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a", "Old")

        patch("/api/v1/admin/configsets/${serviceA.toApiId()}", """{"slug": "service-b", "description": "New"}""")
            .andExpect { status { isOk() } }

        val records = records(serviceA.value).drop(1)
        assertEquals(listOf("CONFIG_SET_RENAMED", "CONFIG_SET_DESCRIBED"), records.map { it["operation"] })
        assertEquals("CONFIG_SET", records[0]["resource_type"])
        assertEquals("${catalog.pathOf(top)}/service-b", records[0]["path"])
        assertEquals("${catalog.pathOf(top)}/service-a", records[0]["previous_path"])
        assertEquals(json("""{"slug": "service-b", "previousSlug": "service-a"}"""), json(records[0]["details"]))
        assertEquals(json("""{"description": "New", "previousDescription": "Old"}"""), json(records[1]["details"]))
    }

    @Test
    fun `a rule write on a ConfigSet records the ConfigSet as the resource`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a")

        mvc
            .put("/api/v1/admin/configsets/${serviceA.toApiId()}/rules/READ") {
                bearer(SUPER_ADMIN)
                jsonBody("""{"subjects": ["public"]}""")
            }.andExpect { status { isOk() } }

        val record = records(serviceA.value).last()
        assertEquals("RULE_PUT", record["operation"])
        assertEquals("CONFIG_SET", record["resource_type"])
        assertEquals("${catalog.pathOf(top)}/service-a", record["path"])
    }

    @Test
    fun `rule writes record the action and the subjects before and after`() {
        val top = catalog.namespace()
        val rules = "/api/v1/admin/namespaces/${top.toApiId()}/rules"

        mvc.put("$rules/READ") {
            bearer(SUPER_ADMIN)
            jsonBody("""{"subjects": ["group:a"]}""")
        }
        mvc.put("$rules/READ") {
            bearer(SUPER_ADMIN)
            jsonBody("""{"subjects": ["group:a", "group:b"]}""")
        }
        mvc.delete("$rules/READ") { bearer(SUPER_ADMIN) }

        val records = records(top.value).drop(1)
        assertEquals(listOf("RULE_PUT", "RULE_PUT", "RULE_DELETED"), records.map { it["operation"] })
        assertEquals("NAMESPACE", records[0]["resource_type"])
        assertEquals(catalog.pathOf(top), records[0]["path"])
        assertEquals(
            json("""{"action": "READ", "subjects": ["group:a"], "previousSubjects": null}"""),
            json(records[0]["details"]),
        )
        assertEquals(
            json("""{"action": "READ", "subjects": ["group:a", "group:b"], "previousSubjects": ["group:a"]}"""),
            json(records[1]["details"]),
        )
        assertEquals(
            json("""{"action": "READ", "previousSubjects": ["group:a", "group:b"]}"""),
            json(records[2]["details"]),
        )
    }

    /** `jsonb` does not keep the key order, so the tests compare JSON trees and not strings. */
    private fun json(value: Any?): JsonNode = mapper.readTree(value.toString())

    private fun patch(url: String, body: String) = mvc.patch(url) {
        bearer(SUPER_ADMIN)
        jsonBody(body)
    }

    private fun records(resourceId: UUID): List<Map<String, Any?>> = jdbc
        .sql("select * from audit_event where resource_id = :id order by id")
        .param("id", resourceId)
        .query()
        .listOfRows()
}
