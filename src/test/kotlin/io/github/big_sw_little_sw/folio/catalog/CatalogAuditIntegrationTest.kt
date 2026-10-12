package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.test.web.servlet.*
import kotlin.test.*

@FolioIntegrationTest
class CatalogAuditIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val configSets: ConfigSets,
    @Autowired private val audit: TestAudit,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), "cli")

    @Test
    fun `a create records the path, the actor and the super-admin flag`() {
        val top = catalog.namespace(description = "Top")
        val slug = catalog.pathOf(top)

        val record = audit.records(top.value).single()
        assertEquals("NAMESPACE_CREATED", record["operation"])
        assertEquals("NAMESPACE", record["resource_type"])
        assertEquals(slug, record["path"])
        assertEquals(null, record["previous_path"])
        assertEquals("root", record["actor_subject"])
        assertEquals(true, record["actor_super_admin"])
        audit.assertDetails("""{"parentId": null, "description": "Top"}""", record)
    }

    @Test
    fun `identity sets the super-admin flag of a record from the bearer token`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "user:alice")

        mvc.send(PATCH, namespace(top), SUPER_ADMIN, """{"description": "By root"}""")
        mvc.send(PATCH, namespace(top), alice, """{"description": "By alice"}""").andExpect { status { isOk() } }

        val records = audit.records(top.value).filter { it["operation"] == "NAMESPACE_DESCRIBED" }
        assertEquals(listOf("root", "alice"), records.map { it["actor_subject"] })
        assertEquals(listOf(true, false), records.map { it["actor_super_admin"] })
    }

    @Test
    fun `a ConfigSet create by a caller with MANAGE records no super-admin flag`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "user:alice")

        val created = configSets.create(alice, top, Slug("service-a"), null, SourceType.GIT)

        val record = audit.records(created.id.value).single()
        assertEquals("CONFIG_SET_CREATED", record["operation"])
        assertEquals("CONFIG_SET", record["resource_type"])
        assertEquals("${catalog.pathOf(top)}/service-a", record["path"])
        assertEquals("cli", record["actor_application_id"])
        assertEquals(false, record["actor_super_admin"])
        audit.assertDetails("""{"namespaceId": "${top.toApiId()}", "sourceType": "GIT", "description": null}""", record)
    }

    @Test
    fun `a PATCH of the slug and the description writes a rename and a describe`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search", "Old")

        mvc.send(PATCH, namespace(search), SUPER_ADMIN, """{"slug": "find", "description": "New"}""")

        audit.assertTrail(search.value, "NAMESPACE_CREATED", "NAMESPACE_RENAMED", "NAMESPACE_DESCRIBED")
        val (renamed, described) = audit.records(search.value).drop(1)
        assertEquals("${catalog.pathOf(top)}/find", renamed["path"])
        assertEquals("${catalog.pathOf(top)}/search", renamed["previous_path"])
        audit.assertDetails("""{"slug": "find", "previousSlug": "search"}""", renamed)
        assertEquals("${catalog.pathOf(top)}/find", described["path"])
        audit.assertDetails("""{"description": "New", "previousDescription": "Old"}""", described)
    }

    @Test
    fun `edits and moves that change nothing write no record`() {
        val top = catalog.namespace(description = "Same")
        val child = catalog.namespace(top, "child")
        val serviceA = catalog.configSet(top, "service-a")

        mvc
            .send(PATCH, namespace(top), SUPER_ADMIN, """{"slug": "${catalog.pathOf(top)}", "description": "Same"}""")
            .andExpect { status { isOk() } }
        mvc.send(PATCH, configSet(serviceA), SUPER_ADMIN, "{}").andExpect { status { isOk() } }
        mvc
            .send(POST, "${configSet(serviceA)}:move", SUPER_ADMIN, """{"namespaceId": "${top.toApiId()}"}""")
            .andExpect { status { isOk() } }
        mvc
            .send(POST, "${namespace(child)}:move", SUPER_ADMIN, """{"parentId": "${top.toApiId()}"}""")
            .andExpect { status { isOk() } }

        audit.assertTrail(top.value, "NAMESPACE_CREATED")
        audit.assertTrail(child.value, "NAMESPACE_CREATED")
        audit.assertTrail(serviceA.value, "CONFIG_SET_CREATED")
    }

    @Test
    fun `rule writes that change nothing write no record`() {
        val serviceA = catalog.configSet(catalog.namespace())
        catalog.rule(serviceA, Action.READ, "public")

        mvc
            .send(PUT, "${configSet(serviceA)}/rules/READ", SUPER_ADMIN, """{"subjects": ["public"]}""")
            .andExpect { status { isOk() } }
        mvc.send(DELETE, "${configSet(serviceA)}/rules/MANAGE", SUPER_ADMIN).andExpect { status { isNoContent() } }

        audit.assertTrail(serviceA.value, "CONFIG_SET_CREATED", "RULE_PUT")
    }

    @Test
    fun `a UI edit with the same description in CRLF form writes only a rename`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search")
        mvc
            .send(PATCH, namespace(search), SUPER_ADMIN, """{"description": "line 1\nline 2"}""")
            .andExpect { status { isOk() } }

        mvc
            .postForm(
                "/namespaces/${search.toApiId()}/edit",
                SUPER_ADMIN,
                "slug" to "find",
                "description" to "line 1\r\nline 2",
            ).andExpect { status { is3xxRedirection() } }

        audit.assertTrail(search.value, "NAMESPACE_CREATED", "NAMESPACE_DESCRIBED", "NAMESPACE_RENAMED")
    }

    @Test
    fun `denied requests and conflicts write no record`() {
        val top = catalog.namespace()
        catalog.namespace(top, "taken")
        val other = catalog.namespace(top, "other")

        mvc.send(PATCH, namespace(other), alice, """{"slug": "free"}""").andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.send(PATCH, namespace(other), SUPER_ADMIN, """{"slug": "taken"}""").andExpectProblem(409, "SLUG_TAKEN")
        mvc.send(DELETE, namespace(top), SUPER_ADMIN).andExpectProblem(409, "NAMESPACE_NOT_EMPTY")

        audit.assertTrail(other.value, "NAMESPACE_CREATED")
        audit.assertTrail(top.value, "NAMESPACE_CREATED")
    }

    @Test
    fun `a move records the new and the previous path, and a delete the path before the delete`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val serviceA = catalog.configSet(from, "service-a")

        mvc
            .send(POST, "${configSet(serviceA)}:move", SUPER_ADMIN, """{"namespaceId": "${to.toApiId()}"}""")
            .andExpect { status { isOk() } }
        mvc.send(DELETE, configSet(serviceA), SUPER_ADMIN).andExpect { status { isNoContent() } }

        audit.assertTrail(serviceA.value, "CONFIG_SET_CREATED", "CONFIG_SET_MOVED", "CONFIG_SET_DELETED")
        val (_, moved, deleted) = audit.records(serviceA.value)
        assertEquals("${catalog.pathOf(top)}/to/service-a", moved["path"])
        assertEquals("${catalog.pathOf(top)}/from/service-a", moved["previous_path"])
        audit.assertDetails("""{"namespaceId": "${to.toApiId()}", "previousNamespaceId": "${from.toApiId()}"}""", moved)
        assertEquals("${catalog.pathOf(top)}/to/service-a", deleted["path"])
    }

    @Test
    fun `a Namespace move records both paths and parents, and a delete the path before the delete`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val child = catalog.namespace(from, "moved")
        val topPath = catalog.pathOf(top)

        mvc
            .send(POST, "${namespace(child)}:move", SUPER_ADMIN, """{"parentId": "${to.toApiId()}"}""")
            .andExpect { status { isOk() } }
        mvc.send(DELETE, namespace(child), SUPER_ADMIN).andExpect { status { isNoContent() } }

        audit.assertTrail(child.value, "NAMESPACE_CREATED", "NAMESPACE_MOVED", "NAMESPACE_DELETED")
        val (_, moved, deleted) = audit.records(child.value)
        assertEquals("$topPath/to/moved", moved["path"])
        assertEquals("$topPath/from/moved", moved["previous_path"])
        audit.assertDetails("""{"parentId": "${to.toApiId()}", "previousParentId": "${from.toApiId()}"}""", moved)
        assertEquals("$topPath/to/moved", deleted["path"])
        assertEquals(null, deleted["previous_path"])
    }

    @Test
    fun `a ConfigSet PATCH of the slug and the description writes a rename and a describe`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a", "Old")

        mvc
            .send(PATCH, configSet(serviceA), SUPER_ADMIN, """{"slug": "service-b", "description": "New"}""")
            .andExpect { status { isOk() } }

        audit.assertTrail(serviceA.value, "CONFIG_SET_CREATED", "CONFIG_SET_RENAMED", "CONFIG_SET_DESCRIBED")
        val (renamed, described) = audit.records(serviceA.value).drop(1)
        assertEquals("CONFIG_SET", renamed["resource_type"])
        assertEquals("${catalog.pathOf(top)}/service-b", renamed["path"])
        assertEquals("${catalog.pathOf(top)}/service-a", renamed["previous_path"])
        audit.assertDetails("""{"slug": "service-b", "previousSlug": "service-a"}""", renamed)
        audit.assertDetails("""{"description": "New", "previousDescription": "Old"}""", described)
    }

    @Test
    fun `a rule write on a ConfigSet records the ConfigSet as the resource`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a")

        mvc
            .send(PUT, "${configSet(serviceA)}/rules/READ", SUPER_ADMIN, """{"subjects": ["public"]}""")
            .andExpect { status { isOk() } }

        val record = audit.records(serviceA.value).last()
        assertEquals("RULE_PUT", record["operation"])
        assertEquals("CONFIG_SET", record["resource_type"])
        assertEquals("${catalog.pathOf(top)}/service-a", record["path"])
    }

    @Test
    fun `rule writes record the action and the subjects before and after`() {
        val top = catalog.namespace()
        val rules = "${namespace(top)}/rules/READ"

        mvc.send(PUT, rules, SUPER_ADMIN, """{"subjects": ["group:a"]}""")
        mvc.send(PUT, rules, SUPER_ADMIN, """{"subjects": ["group:a", "group:b"]}""")
        mvc.send(DELETE, rules, SUPER_ADMIN)

        audit.assertTrail(top.value, "NAMESPACE_CREATED", "RULE_PUT", "RULE_PUT", "RULE_DELETED")
        val (put, changed, deleted) = audit.records(top.value).drop(1)
        assertEquals("NAMESPACE", put["resource_type"])
        assertEquals(catalog.pathOf(top), put["path"])
        audit.assertDetails("""{"action": "READ", "subjects": ["group:a"], "previousSubjects": null}""", put)
        audit.assertDetails(
            """{"action": "READ", "subjects": ["group:a", "group:b"], "previousSubjects": ["group:a"]}""",
            changed,
        )
        audit.assertDetails("""{"action": "READ", "previousSubjects": ["group:a", "group:b"]}""", deleted)
    }

    private fun namespace(id: NamespaceId) = "/api/v1/admin/namespaces/${id.toApiId()}"

    private fun configSet(id: ConfigSetId) = "/api/v1/admin/configsets/${id.toApiId()}"
}
