package io.github.big_sw_little_sw.folio.configset.web

import com.jayway.jsonpath.JsonPath
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.security.BOOTSTRAP_ADMIN
import io.github.big_sw_little_sw.folio.source.SshGitServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import kotlin.test.Test
import kotlin.test.assertFalse

/** ConfigSet sources and the `:check` endpoint end to end, against a real SSH Git server (ADR 0004). */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class ConfigSetSourceApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = jwt().jwt { it.subject(BOOTSTRAP_ADMIN) }
    private val alice = jwt().jwt { it.subject("alice") }
    private lateinit var commits: List<String>
    private lateinit var namespaceId: String

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
        SshGitServer.revokeAll()
        commits = SshGitServer.createRepository("api")
        namespaceId = id(post(NAMESPACES, """{"parentId": null, "slug": "production"}"""))
    }

    @Test
    fun `create returns the source`() {
        val credential = credential("test-server")

        post(CONFIG_SETS, create(source(credential))).andExpect {
            status { isCreated() }
            jsonPath("$.source.credentialId") { value(credential) }
            jsonPath("$.source.repositoryPath") { value("repos/api.git") }
            jsonPath("$.source.branch") { value("main") }
            jsonPath("$.source.rootPath") { value("config") }
        }
    }

    @Test
    fun `an invalid source gives 400, an unknown credential 404 and a disabled one 409`() {
        val credential = credential("test-server")
        val invalid =
            listOf(
                source("cred_123"),
                source(credential, repositoryPath = "/org/repo.git"),
                source(credential, repositoryPath = "org/../repo.git"),
                source(credential, repositoryPath = "git@example.com:org/repo.git"),
                source(credential, branch = "refs/../main"),
                source(credential, branch = "a b"),
                source(credential, rootPath = "/config"),
                source(credential, rootPath = "config/../.."),
                source(credential, rootPath = "config\\\\sub"),
            )
        invalid.forEach { post(CONFIG_SETS, create(it)).andExpectProblem(400) }
        post(CONFIG_SETS, """{"namespaceId": "$namespaceId", "slug": "a"}""").andExpectProblem(400)

        post(CONFIG_SETS, create(source("cred_${"0".repeat(32)}"))).andExpectProblem(404).andExpect {
            jsonPath("$.detail") { value("Credential not found") }
        }

        post("$CREDENTIALS/$credential:disable", "").andExpect { status { isOk() } }
        post(CONFIG_SETS, create(source(credential))).andExpectProblem(409).andExpect {
            jsonPath("$.detail") { value("Credential is disabled") }
        }
    }

    @Test
    fun `a caller who may create ConfigSets but not use the credential gets 403`() {
        val credential = credential("test-server")
        send(
            HttpMethod.PUT,
            "$NAMESPACES/$namespaceId/rules/CONFIG_SET_CREATE",
            """{"subjects": ["user:alice"]}""",
            admin,
        )

        send(HttpMethod.POST, CONFIG_SETS, create(source(credential)), alice).andExpectProblem(403).andExpect {
            jsonPath("$.detail") { value("Permission CREDENTIAL_USE denied") }
        }
    }

    @Test
    fun `the check needs view on the ConfigSet and use of its credential`() {
        val configSet = configSet(credential("test-server"))
        val check = "$CONFIG_SETS/$configSet:check"

        send(HttpMethod.POST, check, "", alice).andExpectProblem(403).andExpect {
            jsonPath("$.detail") { value("Permission CONFIG_SET_VIEW denied") }
        }
        send(HttpMethod.PUT, "$CONFIG_SETS/$configSet/rules/CONFIG_SET_VIEW", """{"subjects": ["user:alice"]}""", admin)
        send(HttpMethod.POST, check, "", alice).andExpectProblem(403).andExpect {
            jsonPath("$.detail") { value("Permission CREDENTIAL_USE denied") }
        }
        post("$CONFIG_SETS/cfg_${"0".repeat(32)}:check", "").andExpectProblem(404)
        post(check, """{"keyId": "key_123"}""").andExpectProblem(400)
        post(check, """{"keyId": "key_${"0".repeat(32)}"}""").andExpectProblem(409).andExpect {
            jsonPath("$.detail") { value("Key is not the credential's pending key") }
        }
        // A real pending key, but of another credential.
        val other = credential("test-server")
        val othersPending = JsonPath.read<String>(body(post("$CREDENTIALS/$other:regenerate", "")), "$.keys[1].id")
        post(check, """{"keyId": "$othersPending"}""").andExpectProblem(409).andExpect {
            jsonPath("$.detail") { value("Key is not the credential's pending key") }
        }
    }

    @Test
    fun `a passing check returns ok and the commit at the tip of the branch`() {
        val configSet = configSet(authorizedCredential())

        post("$CONFIG_SETS/$configSet:check", "").andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(true) }
            jsonPath("$.commitId") { value(commits.last()) }
            jsonPath("$.code") { value(null) }
        }
    }

    @Test
    fun `a failed check returns 200 with the code and fixed summary, and no transport output anywhere`(
        output: CapturedOutput,
    ) {
        val authorized = authorizedCredential()
        val failures =
            mapOf(
                configSet(credential("wrong-host-key")) to "HOST_KEY_REJECTED",
                configSet(credential("test-server")) to "AUTH_FAILED",
                configSet(authorized, repositoryPath = "repos/missing.git") to "REPOSITORY_NOT_FOUND",
                configSet(authorized, branch = "missing") to "BRANCH_NOT_FOUND",
                configSet(authorized, rootPath = "missing") to "ROOT_PATH_NOT_FOUND",
                configSet(credential("unreachable")) to "UNREACHABLE",
            )

        failures.forEach { (configSet, code) ->
            val result =
                post("$CONFIG_SETS/$configSet:check", "").andExpect {
                    status { isOk() }
                    jsonPath("$.ok") { value(false) }
                    jsonPath("$.code") { value(code) }
                    jsonPath("$.summary") { isNotEmpty() }
                    jsonPath("$.commitId") { value(null) }
                }
            assertNoTransportOutput(body(result))
        }
        assertNoTransportOutput(output.all)
    }

    @Test
    fun `a pending key is verified by naming it`() {
        val credential = credential("test-server")
        val regenerated = body(post("$CREDENTIALS/$credential:regenerate", ""))
        val pending = JsonPath.read<String>(regenerated, "$.keys[1].id")
        SshGitServer.authorize(JsonPath.read(regenerated, "$.keys[1].publicKey"))
        val configSet = configSet(credential)

        post("$CONFIG_SETS/$configSet:check", """{"keyId": "$pending"}""").andExpect {
            jsonPath("$.ok") { value(true) }
        }
        post("$CONFIG_SETS/$configSet:check", "{}").andExpect {
            jsonPath("$.code") { value("AUTH_FAILED") }
        }
    }

    private fun assertNoTransportOutput(text: String) {
        val transportOutput =
            listOf(
                "ssh://",
                "git@",
                "fatal",
                "Server key",
                "authentication methods",
                "repos/missing",
                "TransportException",
                "SshException",
                "NoRemoteRepository",
                "Exception in thread",
            )
        transportOutput.forEach { assertFalse(text.contains(it), "'$it' appears in:\n$text") }
    }

    private fun authorizedCredential(): String {
        val credential = credential("test-server")
        val created = body(send(HttpMethod.GET, "$CREDENTIALS/$credential", "", admin))
        SshGitServer.authorize(JsonPath.read(created, "$.keys[0].publicKey"))
        return credential
    }

    private fun credential(instance: String) = id(post(CREDENTIALS, """{"gitInstance": "$instance"}"""))

    private fun configSet(
        credentialId: String,
        repositoryPath: String = "repos/api.git",
        branch: String = "main",
        rootPath: String = "config",
    ): String = id(post(CONFIG_SETS, create(source(credentialId, repositoryPath, branch, rootPath))))

    private fun create(source: String) =
        """{"namespaceId": "$namespaceId", "slug": "cfg-${System.nanoTime()}", "source": $source}"""

    private fun source(
        credentialId: String,
        repositoryPath: String = "repos/api.git",
        branch: String = "main",
        rootPath: String = "config",
    ) = """
        {"credentialId": "$credentialId", "repositoryPath": "$repositoryPath", "branch": "$branch",
         "rootPath": "$rootPath"}
        """.trimIndent()

    private fun id(result: ResultActionsDsl): String = JsonPath.read(body(result), "$.id")

    private fun body(result: ResultActionsDsl) = result.andReturn().response.contentAsString

    private fun post(
        url: String,
        body: String,
    ) = send(HttpMethod.POST, url, body, admin)

    private fun send(
        method: HttpMethod,
        url: String,
        body: String,
        token: RequestPostProcessor,
    ) = mvc.request(method, url) {
        with(token)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun ResultActionsDsl.andExpectProblem(expectedStatus: Int) =
        andExpect {
            status { isEqualTo(expectedStatus) }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }

    companion object {
        const val NAMESPACES = "/api/v1/admin/namespaces"
        const val CONFIG_SETS = "/api/v1/admin/configsets"
        const val CREDENTIALS = "/api/v1/admin/credentials"

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
