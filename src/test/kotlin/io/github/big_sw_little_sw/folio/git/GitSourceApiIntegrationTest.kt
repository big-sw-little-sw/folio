package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.*
import java.util.UUID
import kotlin.test.*

/**
 * These tests check onboarding and the Git source route through HTTP. Onboarding does not connect to a Git server, so
 * the credentials of these tests name a Git instance that the configuration does not have.
 */
@GitIntegrationTest
class GitSourceApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val audit: TestAudit,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `create makes the ConfigSet and its Git source without a connection`() {
        val namespace = catalog.namespace()
        val credential = credentials.credential(namespace, gitInstance = "nowhere").id.toApiId()

        val created = create(SUPER_ADMIN, namespace, credential, slug = "service-a", rootPath = "config")

        val id = created.json("$.configSetId")
        created.andExpect {
            status { isCreated() }
            header { string(HttpHeaders.LOCATION, "/api/v1/admin/configsets/$id/git") }
            jsonPath("$.path") { value("${catalog.pathOf(namespace)}/service-a") }
            jsonPath("$.credentialId") { value(credential) }
            jsonPath("$.repositoryPath") { value("org/repo.git") }
            jsonPath("$.branch") { value("main") }
            jsonPath("$.rootPath") { value("config") }
            jsonPath("$.lastPublishedCommit") { value(null) }
        }
        mvc.get("/api/v1/admin/configsets/$id/git") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.configSetId") { value(id) }
            jsonPath("$.rootPath") { value("config") }
        }
        mvc.get("/api/v1/admin/configsets/$id") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.sourceType") { value("GIT") }
        }
    }

    @Test
    fun `an absent root path is the root of the repository`() {
        val namespace = catalog.namespace()
        val credential = credentials.credential(namespace).id.toApiId()

        create(SUPER_ADMIN, namespace, credential, rootPath = null).andExpect {
            status { isCreated() }
            jsonPath("$.rootPath") { value("") }
        }
    }

    @Test
    fun `create writes a ConfigSet record and a Git source record`() {
        val namespace = catalog.namespace()
        val credential = credentials.credential(namespace, gitInstance = "nowhere")

        val id = create(SUPER_ADMIN, namespace, credential.id.toApiId(), slug = "audited").json("$.configSetId")

        val configSet = CONFIG_SET_ID.parse(id).value
        audit.assertTrail(configSet, "CONFIG_SET_CREATED", "GIT_SOURCE_CREATED")
        val git = audit.records(configSet).last()
        assertEquals("GIT_SOURCE", git["resource_type"])
        assertEquals("${catalog.pathOf(namespace)}/audited", git["path"])
        audit.assertDetails(
            """
            {"credentialId": "${credential.id.toApiId()}", "gitInstance": "nowhere",
             "repositoryPath": "org/repo.git", "branch": "main", "rootPath": "config"}
            """,
            git,
        )
    }

    @Test
    fun `create needs MANAGE on the Namespace and on the Namespace of the credential`() {
        val target = catalog.namespace()
        val owner = catalog.namespace()
        val credential = credentials.credential(owner).id.toApiId()

        catalog.rule(target, Action.MANAGE, "user:alice")
        create(alice, target, credential).andExpectForbidden(Action.MANAGE, owner.toApiId())

        catalog.rule(owner, Action.MANAGE, "user:alice")
        val other = catalog.namespace()
        create(alice, other, credential).andExpectForbidden(Action.MANAGE, other.toApiId())

        create(alice, target, credential).andExpect { status { isCreated() } }
    }

    @Test
    fun `a failed create leaves no ConfigSet`() {
        val target = catalog.namespace()
        val owner = catalog.namespace()
        val credential = credentials.credential(owner).id.toApiId()
        catalog.rule(target, Action.MANAGE, "user:alice")
        catalog.rule(target, Action.READ, "user:alice")

        create(alice, target, credential, slug = "denied").andExpectForbidden(Action.MANAGE, owner.toApiId())

        mvc.get("/api/v1/admin/namespaces/${target.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            jsonPath("$.configSets.length()") { value(0) }
        }
    }

    @Test
    fun `create rejects values that are not valid, before it finds a resource`() {
        val namespace = catalog.namespace()
        val credential = credentials.credential(namespace).id.toApiId()
        val missing = NamespaceId(UUID.randomUUID())

        listOf(
            body(missing, credential, slug = "Bad") to "INVALID_SLUG",
            body(missing, "cred_1") to "INVALID_ID",
            body(missing, credential, repositoryPath = "../repo") to "INVALID_REPOSITORY_PATH",
            body(missing, credential, repositoryPath = "git@host:repo") to "INVALID_REPOSITORY_PATH",
            body(missing, credential, branch = "a..b") to "INVALID_BRANCH",
            body(missing, credential, rootPath = "../config") to "INVALID_ROOT_PATH",
            body(missing, credential, rootPath = "config/") to "INVALID_ROOT_PATH",
            """{"namespaceId": "${namespace.toApiId()}", "slug": "x"}""" to "INVALID_REQUEST",
        ).forEach { (body, code) ->
            mvc.send(HttpMethod.POST, CREATE, SUPER_ADMIN, body).andExpectProblem(400, code)
        }
    }

    @Test
    fun `create fails for a missing Namespace or credential, a disabled credential and a taken slug`() {
        val namespace = catalog.namespace()
        val credential = credentials.credential(namespace)

        create(SUPER_ADMIN, NamespaceId(UUID.randomUUID()), credential.id.toApiId())
            .andExpectProblem(404, "NAMESPACE_NOT_FOUND")
        create(SUPER_ADMIN, namespace, "cred_${"0".repeat(32)}").andExpectProblem(404, "CREDENTIAL_NOT_FOUND")
        create(SUPER_ADMIN, namespace, credential.id.toApiId(), slug = "taken").andExpect { status { isCreated() } }
        create(SUPER_ADMIN, namespace, credential.id.toApiId(), slug = "taken").andExpectProblem(409, "SLUG_TAKEN")
        credentials.disable(credential.id)
        create(SUPER_ADMIN, namespace, credential.id.toApiId()).andExpectProblem(409, "CREDENTIAL_DISABLED")
    }

    @Test
    fun `the Git source needs MANAGE on the ConfigSet`() {
        val namespace = catalog.namespace()
        val id = create(SUPER_ADMIN, namespace, credentials.credential(namespace).id.toApiId()).json("$.configSetId")
        catalog.rule(namespace, Action.READ, "user:alice")

        mvc.get("/api/v1/admin/configsets/$id/git") { bearer(alice) }.andExpectForbidden(Action.MANAGE, id)
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        mvc.get("/api/v1/admin/configsets/$id/git") { bearer(alice) }.andExpect { status { isOk() } }
        mvc
            .get("/api/v1/admin/configsets/cfg_${"0".repeat(32)}/git") { bearer(SUPER_ADMIN) }
            .andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
    }

    @Test
    fun `a ConfigSet without a Git source has no Git source route`() {
        val configSet = catalog.configSet(catalog.namespace())

        mvc
            .get("/api/v1/admin/configsets/${configSet.toApiId()}/git") { bearer(SUPER_ADMIN) }
            .andExpectProblem(404, "GIT_SOURCE_NOT_FOUND")
    }

    @Test
    fun `the Source definition does not change after creation`() {
        val namespace = catalog.namespace()
        val id = create(SUPER_ADMIN, namespace, credentials.credential(namespace).id.toApiId()).json("$.configSetId")

        mvc
            .send(HttpMethod.PATCH, "/api/v1/admin/configsets/$id/git", SUPER_ADMIN, """{"branch": "other"}""")
            .andExpectProblem(405, "METHOD_NOT_ALLOWED")
        mvc
            .send(HttpMethod.PATCH, "/api/v1/admin/configsets/$id", SUPER_ADMIN, """{"slug": "renamed"}""")
            .andExpect { status { isOk() } }

        mvc.get("/api/v1/admin/configsets/$id/git") { bearer(SUPER_ADMIN) }.andExpect {
            jsonPath("$.path") { value("${catalog.pathOf(namespace)}/renamed") }
            jsonPath("$.branch") { value("main") }
            jsonPath("$.repositoryPath") { value("org/repo.git") }
        }
    }

    @Test
    fun `a credential that a Git source uses is not deleted until its ConfigSet is deleted`() {
        val namespace = catalog.namespace()
        val credential = credentials.credential(namespace).id.toApiId()
        val id = create(SUPER_ADMIN, namespace, credential).json("$.configSetId")

        mvc
            .delete("/api/v1/admin/credentials/$credential") { bearer(SUPER_ADMIN) }
            .andExpectProblem(409, "CREDENTIAL_IN_USE")
        mvc.get("/api/v1/admin/credentials/$credential") { bearer(SUPER_ADMIN) }.andExpect { status { isOk() } }

        mvc.delete("/api/v1/admin/configsets/$id") { bearer(SUPER_ADMIN) }.andExpect { status { isNoContent() } }
        mvc
            .delete("/api/v1/admin/credentials/$credential") { bearer(SUPER_ADMIN) }
            .andExpect { status { isNoContent() } }
    }

    private fun create(
        caller: Caller.Authenticated,
        namespace: NamespaceId,
        credential: String,
        slug: String = TestCatalog.uniqueSlug(),
        rootPath: String? = "config",
    ): ResultActionsDsl =
        mvc.send(HttpMethod.POST, CREATE, caller, body(namespace, credential, slug = slug, rootPath = rootPath))

    private fun body(
        namespace: NamespaceId,
        credential: String,
        slug: String = TestCatalog.uniqueSlug(),
        repositoryPath: String = "org/repo.git",
        branch: String = "main",
        rootPath: String? = "config",
    ): String {
        val root = if (rootPath == null) "" else """, "rootPath": "$rootPath""""
        return """
            {"namespaceId": "${namespace.toApiId()}", "slug": "$slug", "credentialId": "$credential",
             "repositoryPath": "$repositoryPath", "branch": "$branch"$root}
        """.trimIndent()
    }

    private companion object {
        const val CREATE = "/api/v1/admin/git/configsets"
    }
}
