package io.github.big_sw_little_sw.folio.namespace.web

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

/** The namespace and policy admin API end to end, with tokens from Spring Security's `jwt()` support. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class NamespaceApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = token(BOOTSTRAP_ADMIN)
    private val alice = token("alice", "editors")

    @BeforeEach
    fun deleteAllNamespaces() {
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
    }

    @Test
    fun `requests without a token are rejected with 401`() {
        mvc.get(NAMESPACES).andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")) }
        }
    }

    @Test
    fun `a bootstrap admin creates namespaces whose prefixed IDs round-trip`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")

        mvc.get("$NAMESPACES/$ai") { with(admin) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(ai) }
            jsonPath("$.parentId") { value(engineering) }
            jsonPath("$.slug") { value("ai") }
        }
        mvc.get("$NAMESPACES?parentId=$engineering") { with(admin) }.andExpect {
            jsonPath("$[*].id") { value(contains(ai)) }
        }
        mvc.get(NAMESPACES) { with(admin) }.andExpect { jsonPath("$[*].id") { value(contains(engineering)) } }
    }

    @Test
    fun `create returns 201 with the new namespace's location`() {
        send(POST, NAMESPACES, """{"slug": "engineering"}""").andExpect {
            status { isCreated() }
            header { string(HttpHeaders.LOCATION, matchesPattern("$NAMESPACES/ns_[0-9a-f]{32}")) }
            jsonPath("$.id") { value(matchesPattern("ns_[0-9a-f]{32}")) }
        }
    }

    @Test
    fun `a bootstrap admin renames, moves and deletes namespaces`() {
        val engineering = create(null, "engineering")
        val platform = create(null, "platform")
        val ai = create(engineering, "ai")

        send(POST, "$NAMESPACES/$ai:rename", """{"slug": "ml"}""").andExpect {
            status { isOk() }
            jsonPath("$.slug") { value("ml") }
        }
        send(POST, "$NAMESPACES/$ai:move", """{"parentId": "$platform"}""").andExpect {
            status { isOk() }
            jsonPath("$.parentId") { value(platform) }
        }
        send(POST, "$NAMESPACES/$ai:move", "{}").andExpect { jsonPath("$.parentId") { doesNotExist() } }
        mvc.delete("$NAMESPACES/$ai") { with(admin) }.andExpect { status { isNoContent() } }
        mvc.get("$NAMESPACES/$ai") { with(admin) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `without a rule a caller is denied with 403`() {
        val engineering = create(null, "engineering")

        mvc.get("$NAMESPACES/$engineering") { with(alice) }.andExpectProblem(403)
        send(POST, NAMESPACES, """{"slug": "other"}""", alice).andExpectProblem(403)
        mvc.get(NAMESPACES) { with(alice) }.andExpect { jsonPath("$") { isEmpty() } }
    }

    @Test
    fun `a bootstrap admin adds, lists and removes rules`() {
        val engineering = create(null, "engineering")
        val rules = "$NAMESPACES/$engineering/rules"

        send(PUT, "$rules/NAMESPACE_VIEW", """{"subjects": ["user:alice", "group:editors"]}""").andExpect {
            status { isOk() }
            jsonPath("$.action") { value("NAMESPACE_VIEW") }
        }
        grant(engineering, "NAMESPACE_DELETE", "public")

        mvc.get(rules) { with(admin) }.andExpect {
            jsonPath("$[0].action") { value("NAMESPACE_DELETE") }
            jsonPath("$[0].subjects") { value(contains("public")) }
            jsonPath("$[1].action") { value("NAMESPACE_VIEW") }
            jsonPath("$[1].subjects") { value(contains("group:editors", "user:alice")) }
        }
        mvc.delete("$rules/NAMESPACE_DELETE") { with(admin) }.andExpect { status { isNoContent() } }
        mvc.get(rules) { with(admin) }.andExpect { jsonPath("$[*].action") { value(contains("NAMESPACE_VIEW")) } }
    }

    @Test
    fun `a grant on an ancestor applies to its descendants`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        val hive = create(ai, "hive")
        grant(engineering, "NAMESPACE_VIEW", "group:editors")
        grant(engineering, "NAMESPACE_CREATE", "group:editors")

        mvc.get("$NAMESPACES/$hive") { with(alice) }.andExpect { status { isOk() } }
        send(POST, NAMESPACES, """{"parentId": "$hive", "slug": "x"}""", alice).andExpect { status { isCreated() } }
    }

    @Test
    fun `a nearer rule overrides an inherited one`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        grant(engineering, "NAMESPACE_VIEW", "group:editors")
        grant(ai, "NAMESPACE_VIEW", "user:bob")

        mvc.get("$NAMESPACES/$engineering") { with(alice) }.andExpect { status { isOk() } }
        mvc.get("$NAMESPACES/$ai") { with(alice) }.andExpectProblem(403)
        mvc.get("$NAMESPACES/$ai") { with(token("bob")) }.andExpect { status { isOk() } }
    }

    @Test
    fun `public and authenticated subjects grant to any caller with a token`() {
        val open = create(null, "open")
        val internal = create(null, "internal")
        create(null, "closed")
        grant(open, "NAMESPACE_VIEW", "public")
        grant(internal, "NAMESPACE_VIEW", "authenticated")

        mvc.get(NAMESPACES) { with(alice) }.andExpect { jsonPath("$[*].slug") { value(contains("internal", "open")) } }
    }

    @Test
    fun `explain reports the deciding rule and the matched subject`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        grant(engineering, "NAMESPACE_VIEW", "group:editors")

        explain(ai, """{"action": "NAMESPACE_VIEW", "principal": {"subject": "alice", "groups": ["editors"]}}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.allowed") { value(true) }
                jsonPath("$.action") { value("NAMESPACE_VIEW") }
                jsonPath("$.resourceId") { value(ai) }
                jsonPath("$.reason") { value("RULE_MATCHED") }
                jsonPath("$.policySource") { value(engineering) }
                jsonPath("$.matchedSubject") { value("group:editors") }
            }
        explain(ai, """{"action": "NAMESPACE_VIEW"}""").andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("RULE_NOT_MATCHED") }
            jsonPath("$.policySource") { value(engineering) }
            jsonPath("$.matchedSubject") { doesNotExist() }
        }
    }

    @Test
    fun `explain reports default deny and bootstrap admins`() {
        val engineering = create(null, "engineering")

        explain(engineering, """{"action": "NAMESPACE_DELETE", "principal": {"subject": "alice"}}""").andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("NO_RULE") }
            jsonPath("$.policySource") { doesNotExist() }
        }
        explain(engineering, """{"action": "NAMESPACE_DELETE", "principal": {"subject": "$BOOTSTRAP_ADMIN"}}""")
            .andExpect {
                jsonPath("$.allowed") { value(true) }
                jsonPath("$.reason") { value("BOOTSTRAP_ADMIN") }
                jsonPath("$.matchedSubject") { value("user:$BOOTSTRAP_ADMIN") }
            }
    }

    @Test
    fun `explaining and managing rules need policy permissions`() {
        val engineering = create(null, "engineering")
        val rules = "$NAMESPACES/$engineering/rules"

        send(POST, "$NAMESPACES/$engineering:explain", """{"action": "NAMESPACE_VIEW"}""", alice)
            .andExpectProblem(403)
        mvc.get(rules) { with(alice) }.andExpectProblem(403)
        send(PUT, "$rules/NAMESPACE_VIEW", """{"subjects": ["user:alice"]}""", alice).andExpectProblem(403)

        grant(engineering, "POLICY_VIEW", "user:alice")
        mvc.get(rules) { with(alice) }.andExpect { status { isOk() } }
    }

    @Test
    fun `removing a rule needs policy update`() {
        val engineering = create(null, "engineering")
        grant(engineering, "NAMESPACE_VIEW", "public")
        grant(engineering, "POLICY_VIEW", "user:alice")

        mvc.delete("$NAMESPACES/$engineering/rules/NAMESPACE_VIEW") { with(alice) }.andExpectProblem(403)
    }

    @Test
    fun `a caller holding policy view may explain decisions`() {
        val engineering = create(null, "engineering")
        grant(engineering, "POLICY_VIEW", "group:editors")

        send(POST, "$NAMESPACES/$engineering:explain", """{"action": "POLICY_VIEW"}""", alice).andExpect {
            status { isOk() }
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("RULE_NOT_MATCHED") }
        }
    }

    @Test
    fun `unknown namespaces give 404 problem details`() {
        mvc.get("$NAMESPACES/ns_${"0".repeat(32)}") { with(admin) }.andExpectProblem(404)
    }

    @Test
    fun `invalid input gives 400 problem details`() {
        val engineering = create(null, "engineering")
        val rules = "$NAMESPACES/$engineering/rules"

        mvc.get("$NAMESPACES/not-an-id") { with(admin) }.andExpectProblem(400)
        mvc.get("$NAMESPACES?parentId=ns_123") { with(admin) }.andExpectProblem(400)
        send(POST, NAMESPACES, """{"slug": "Not A Slug"}""").andExpectProblem(400)
        send(POST, NAMESPACES, "{").andExpectProblem(400)
        send(PUT, "$rules/NO_SUCH_ACTION", """{"subjects": ["public"]}""").andExpectProblem(400)
        send(PUT, "$rules/NAMESPACE_VIEW", """{"subjects": ["role:x"]}""").andExpectProblem(400)
        send(PUT, "$rules/NAMESPACE_VIEW", """{"subjects": []}""").andExpectProblem(400)
    }

    @Test
    fun `conflicts with the tree give 409 problem details`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")

        send(POST, NAMESPACES, """{"slug": "engineering"}""").andExpectProblem(409)
        send(POST, "$NAMESPACES/$engineering:move", """{"parentId": "$ai"}""").andExpectProblem(409)
        mvc.delete("$NAMESPACES/$engineering") { with(admin) }.andExpectProblem(409)
    }

    private fun create(
        parentId: String?,
        slug: String,
    ): String {
        val parent = parentId?.let { "\"$it\"" } ?: "null"
        val response =
            send(POST, NAMESPACES, """{"parentId": $parent, "slug": "$slug"}""")
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun grant(
        namespaceId: String,
        action: String,
        subject: String,
    ) {
        send(PUT, "$NAMESPACES/$namespaceId/rules/$action", """{"subjects": ["$subject"]}""")
            .andExpect { status { isOk() } }
    }

    private fun explain(
        namespaceId: String,
        body: String,
    ) = send(POST, "$NAMESPACES/$namespaceId:explain", body)

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
    }
}
