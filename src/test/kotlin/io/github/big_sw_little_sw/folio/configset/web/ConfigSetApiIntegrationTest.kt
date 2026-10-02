package io.github.big_sw_little_sw.folio.configset.web

import com.jayway.jsonpath.JsonPath
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.security.BOOTSTRAP_ADMIN
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ConfigSet admin API, its rules and path resolution end to end. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ConfigSetApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = token(BOOTSTRAP_ADMIN)
    private val alice = token("alice", "editors")

    private lateinit var credentialId: String

    @BeforeEach
    fun deleteAll() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
        credentialId = createdId(send(POST, CREDENTIALS, """{"gitInstance": "example"}"""))
    }

    @Test
    fun `requests without a token are rejected with 401, resolve included`() {
        listOf("$CONFIG_SETS?namespaceId=ns_${"0".repeat(32)}", "$RESOLVE?path=a/b").forEach { url ->
            mvc.get(url).andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")) }
            }
        }
    }

    @Test
    fun `create returns 201 with the new ConfigSet's location`() {
        val production = namespace(null, "production")

        send(POST, CONFIG_SETS, create(production, "service-a")).andExpect {
            status { isCreated() }
            header { string(HttpHeaders.LOCATION, matchesPattern("$CONFIG_SETS/cfg_[0-9a-f]{32}")) }
            jsonPath("$.id") { value(matchesPattern("cfg_[0-9a-f]{32}")) }
            jsonPath("$.namespaceId") { value(production) }
            jsonPath("$.slug") { value("service-a") }
            jsonPath("$.source.credentialId") { value(credentialId) }
            jsonPath("$.source.repositoryPath") { value("org/repo.git") }
            jsonPath("$.source.branch") { value("main") }
            jsonPath("$.source.rootPath") { value("config") }
        }
    }

    @Test
    fun `a bootstrap admin gets, lists, renames, moves and deletes ConfigSets`() {
        val development = namespace(null, "development")
        val production = namespace(null, "production")
        val serviceA = configSet(development, "service-a")

        mvc.get("$CONFIG_SETS/$serviceA") { with(admin) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(serviceA) }
            jsonPath("$.namespaceId") { value(development) }
        }
        mvc.get("$CONFIG_SETS?namespaceId=$development") { with(admin) }.andExpect {
            jsonPath("$[*].id") { value(contains(serviceA)) }
        }
        send(POST, "$CONFIG_SETS/$serviceA:rename", """{"slug": "service-b"}""").andExpect {
            status { isOk() }
            jsonPath("$.slug") { value("service-b") }
        }
        send(POST, "$CONFIG_SETS/$serviceA:move", """{"namespaceId": "$production"}""").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(serviceA) }
            jsonPath("$.namespaceId") { value(production) }
        }
        mvc.delete("$CONFIG_SETS/$serviceA") { with(admin) }.andExpect { status { isNoContent() } }
        mvc.get("$CONFIG_SETS/$serviceA") { with(admin) }.andExpectProblem(404)
    }

    @Test
    fun `resolve returns the ID and canonical path, also after an ancestor is renamed`() {
        val engineering = namespace(null, "engineering")
        val ai = namespace(engineering, "ai")
        val serviceA = configSet(ai, "service-a")

        resolve("engineering/ai/service-a").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(serviceA) }
            jsonPath("$.path") { value("engineering/ai/service-a") }
        }

        send(POST, "$NAMESPACES/$ai:rename", """{"slug": "ml"}""").andExpect { status { isOk() } }

        resolve("engineering/ml/service-a").andExpect { jsonPath("$.id") { value(serviceA) } }
        resolve("engineering/ai/service-a").andExpectProblem(404)
    }

    @Test
    fun `deleting a namespace that holds a ConfigSet gives the namespace conflict`() {
        val production = namespace(null, "production")
        configSet(production, "service-a")

        mvc.delete("$NAMESPACES/$production") { with(admin) }.andExpectProblem(409).andExpect {
            jsonPath("$.detail") { value("Namespace is not empty") }
        }
    }

    @Test
    fun `a duplicate slug in the namespace gives 409`() {
        val production = namespace(null, "production")
        configSet(production, "service-a")

        send(POST, CONFIG_SETS, create(production, "service-a")).andExpectProblem(409)
    }

    @Test
    fun `a bootstrap admin adds, lists and removes ConfigSet rules`() {
        val serviceA = configSet(namespace(null, "production"), "service-a")
        val rules = "$CONFIG_SETS/$serviceA/rules"

        send(PUT, "$rules/CONFIG_SET_VIEW", """{"subjects": ["user:alice", "group:editors"]}""").andExpect {
            status { isOk() }
            jsonPath("$.action") { value("CONFIG_SET_VIEW") }
        }
        mvc.get(rules) { with(admin) }.andExpect {
            jsonPath("$[0].action") { value("CONFIG_SET_VIEW") }
            jsonPath("$[0].subjects") { value(contains("group:editors", "user:alice")) }
        }
        mvc.delete("$rules/CONFIG_SET_VIEW") { with(admin) }.andExpect { status { isNoContent() } }
        mvc.get(rules) { with(admin) }.andExpect { jsonPath("$") { isEmpty() } }
    }

    @Test
    fun `a ConfigSet's own rule overrides its namespace's rule`() {
        val production = namespace(null, "production")
        val serviceA = configSet(production, "service-a")
        val serviceB = configSet(production, "service-b")
        grant(NAMESPACES, production, "CONFIG_SET_VIEW", "group:editors")
        grant(CONFIG_SETS, serviceA, "CONFIG_SET_VIEW", "user:bob")

        mvc.get("$CONFIG_SETS/$serviceB") { with(alice) }.andExpect { status { isOk() } }
        mvc.get("$CONFIG_SETS/$serviceA") { with(alice) }.andExpectProblem(403)
        mvc.get("$CONFIG_SETS/$serviceA") { with(token("bob")) }.andExpect { status { isOk() } }
        mvc.get("$CONFIG_SETS?namespaceId=$production") { with(alice) }.andExpect {
            jsonPath("$[*].id") { value(contains(serviceB)) }
        }
    }

    @Test
    fun `explain on a ConfigSet reports a ConfigSet or namespace as policy source`() {
        val production = namespace(null, "production")
        val serviceA = configSet(production, "service-a")
        grant(NAMESPACES, production, "CONFIG_SET_VIEW", "group:editors")
        grant(CONFIG_SETS, serviceA, "CONFIG_SET_DELETE", "user:bob")
        val principal = """"principal": {"subject": "alice", "groups": ["editors"]}"""

        explain(serviceA, """{"action": "CONFIG_SET_VIEW", $principal}""").andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(true) }
            jsonPath("$.resourceId") { value(serviceA) }
            jsonPath("$.reason") { value("RULE_MATCHED") }
            jsonPath("$.policySource") { value(production) }
            jsonPath("$.matchedSubject") { value("group:editors") }
        }
        explain(serviceA, """{"action": "CONFIG_SET_DELETE", $principal}""").andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("RULE_NOT_MATCHED") }
            jsonPath("$.policySource") { value(serviceA) }
        }
    }

    @Test
    fun `without a rule every endpoint by ID denies with 403`() {
        val production = namespace(null, "production")
        val target = namespace(null, "target")
        val serviceA = configSet(production, "service-a")
        val item = "$CONFIG_SETS/$serviceA"

        send(POST, CONFIG_SETS, create(production, "other"), alice).andExpectProblem(403)
        send(GET, item, "", alice).andExpectProblem(403)
        send(POST, "$item:rename", """{"slug": "other"}""", alice).andExpectProblem(403)
        send(POST, "$item:move", """{"namespaceId": "$target"}""", alice).andExpectProblem(403)
        send(DELETE, item, "", alice).andExpectProblem(403)
        send(GET, "$item/rules", "", alice).andExpectProblem(403)
        send(PUT, "$item/rules/CONFIG_SET_VIEW", """{"subjects": ["public"]}""", alice).andExpectProblem(403)
        send(DELETE, "$item/rules/CONFIG_SET_VIEW", "", alice).andExpectProblem(403)
        send(POST, "$item:explain", """{"action": "CONFIG_SET_VIEW"}""", alice).andExpectProblem(403)
        mvc.get("$CONFIG_SETS?namespaceId=$production") { with(alice) }.andExpect { jsonPath("$") { isEmpty() } }
    }

    @Test
    fun `a move needs move on the ConfigSet and create on the target namespace`() {
        val target = namespace(null, "target")
        val serviceA = configSet(namespace(null, "production"), "service-a")
        grant(CONFIG_SETS, serviceA, "CONFIG_SET_MOVE", "user:alice")
        val move = """{"namespaceId": "$target"}"""

        send(POST, "$CONFIG_SETS/$serviceA:move", move, alice).andExpectProblem(403)

        grant(NAMESPACES, target, "CONFIG_SET_CREATE", "user:alice")
        send(POST, "$CONFIG_SETS/$serviceA:move", move, alice).andExpect { status { isOk() } }
    }

    @Test
    fun `missing resources give 404 whoever asks`() {
        val missingConfigSet = "cfg_${"0".repeat(32)}"
        val missingNamespace = "ns_${"0".repeat(32)}"
        configSet(namespace(null, "production"), "service-a")

        mvc.get("$CONFIG_SETS/$missingConfigSet") { with(alice) }.andExpectProblem(404)
        mvc.get("$CONFIG_SETS/$missingConfigSet/rules") { with(admin) }.andExpectProblem(404)
        mvc.get("$CONFIG_SETS?namespaceId=$missingNamespace") { with(admin) }.andExpectProblem(404)
        send(POST, CONFIG_SETS, create(missingNamespace, "a")).andExpectProblem(404)
        mvc.get("$RESOLVE?path=production/missing") { with(alice) }.andExpectProblem(404)
    }

    @Test
    fun `resolve answers a path the caller may not view exactly like a missing one`() {
        configSet(namespace(null, "production"), "service-a")

        val denied = resolveAs(alice, "production/service-a").andExpectProblem(404)
        val missing = resolveAs(alice, "production/service-b").andExpectProblem(404)

        assertEquals(missing.andReturn().response.contentAsString, denied.andReturn().response.contentAsString)
    }

    @Test
    fun `invalid IDs, slugs and paths give 400 problem details`() {
        val production = namespace(null, "production")
        val serviceA = configSet(production, "service-a")

        mvc.get("$CONFIG_SETS/$production") { with(admin) }.andExpectProblem(400)
        mvc.get("$CONFIG_SETS/cfg_123") { with(admin) }.andExpectProblem(400)
        mvc.get(CONFIG_SETS) { with(admin) }.andExpectProblem(400)
        mvc.get("$CONFIG_SETS?namespaceId=$serviceA") { with(admin) }.andExpectProblem(400)
        send(POST, CONFIG_SETS, create(production, "Not A Slug")).andExpectProblem(400)
        send(POST, CONFIG_SETS, """{"slug": "service-b"}""").andExpectProblem(400)
        send(POST, "$CONFIG_SETS/$serviceA:move", """{"namespaceId": "ns_123"}""").andExpectProblem(400)
        send(PUT, "$CONFIG_SETS/$serviceA/rules/NO_SUCH_ACTION", """{"subjects": ["public"]}""").andExpectProblem(400)
        listOf("", "service-a", "/production/service-a", "production/service-a/", "production//service-a").forEach {
            resolve(it).andExpectProblem(400)
        }
        mvc.get(RESOLVE) { with(admin) }.andExpectProblem(400)
    }

    private fun namespace(
        parentId: String?,
        slug: String,
    ): String {
        val parent = parentId?.let { "\"$it\"" } ?: "null"
        return createdId(send(POST, NAMESPACES, """{"parentId": $parent, "slug": "$slug"}"""))
    }

    private fun configSet(
        namespaceId: String,
        slug: String,
    ): String = createdId(send(POST, CONFIG_SETS, create(namespaceId, slug)))

    /** The source never reaches a Git service here; ConfigSetSourceApiIntegrationTest covers sources. */
    private fun create(
        namespaceId: String,
        slug: String,
    ) = """
        {"namespaceId": "$namespaceId", "slug": "$slug",
         "source": {"credentialId": "$credentialId", "repositoryPath": "org/repo.git", "branch": "main",
                    "rootPath": "config"}}
        """.trimIndent()

    private fun createdId(result: ResultActionsDsl): String =
        JsonPath.read(
            result
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    private fun grant(
        collection: String,
        id: String,
        action: String,
        subject: String,
    ) {
        send(PUT, "$collection/$id/rules/$action", """{"subjects": ["$subject"]}""").andExpect { status { isOk() } }
    }

    private fun explain(
        configSetId: String,
        body: String,
    ) = send(POST, "$CONFIG_SETS/$configSetId:explain", body)

    private fun resolve(path: String) = resolveAs(admin, path)

    private fun resolveAs(
        token: RequestPostProcessor,
        path: String,
    ) = mvc.get(RESOLVE) {
        with(token)
        param("path", path)
    }

    private fun send(
        method: HttpMethod,
        url: String,
        body: String,
        token: RequestPostProcessor = admin,
    ) = mvc.request(method, url) {
        with(token)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun ResultActionsDsl.andExpectProblem(expectedStatus: Int) =
        andExpect {
            status { isEqualTo(expectedStatus) }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(expectedStatus) }
        }

    private fun token(
        subject: String,
        vararg groups: String,
    ): RequestPostProcessor = jwt().jwt { it.subject(subject).claim("groups", groups.toList()) }

    private companion object {
        const val NAMESPACES = "/api/v1/admin/namespaces"
        const val CONFIG_SETS = "/api/v1/admin/configsets"
        const val CREDENTIALS = "/api/v1/admin/credentials"
        const val RESOLVE = "/api/v1/configsets:resolve"
    }
}
