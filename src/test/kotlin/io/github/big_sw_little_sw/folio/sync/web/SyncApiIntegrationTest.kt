package io.github.big_sw_little_sw.folio.sync.web

import com.jayway.jsonpath.JsonPath
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.credential.uniqueCredentialName
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The manual sync and sync state endpoints. Nothing here contacts a Git service. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SyncApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val admin = jwt().jwt { it.subject(SUPER_ADMIN) }
    private val alice = jwt().jwt { it.subject("alice") }
    private lateinit var configSet: String

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
        val namespace = create(NAMESPACES, """{"parentId": null, "slug": "production"}""")
        val credential = create(CREDENTIALS, """{"gitInstance": "example", "name": "${uniqueCredentialName()}"}""")
        val source =
            """{"credentialId": "$credential", "repositoryPath": "org/repo.git", "branch": "main", "rootPath": ""}"""
        configSet = create(CONFIG_SETS, """{"namespaceId": "$namespace", "slug": "app", "source": $source}""")
    }

    @Test
    fun `a sync request needs CONFIG_SET_SYNC and answers 202 with the state, due now`() {
        jdbc.sql("update sync_state set next_due_at = now() + interval '1 day'").update()
        grant("CONFIG_SET_VIEW")

        send(HttpMethod.POST, "$CONFIG_SETS/$configSet:sync", "", alice).andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("Permission CONFIG_SET_SYNC denied") }
        }
        assertFalse(isDue())

        grant("CONFIG_SET_SYNC")
        send(HttpMethod.POST, "$CONFIG_SETS/$configSet:sync", "", alice).andExpect {
            status { isAccepted() }
            jsonPath("$.configSetId") { value(configSet) }
            jsonPath("$.lastSyncedRevision") { value(null) }
            jsonPath("$.nextDueAt") { isNotEmpty() }
        }
        assertTrue(isDue())
    }

    @Test
    fun `the sync state needs CONFIG_SET_VIEW and shows the last attempt`() {
        jdbc
            .sql(
                """
                update sync_state set last_seen_revision = :revision, last_synced_revision = :revision,
                    last_attempt_at = now(), last_success_at = now() - interval '1 hour',
                    last_error_code = 'AUTH_FAILED', last_error_summary = 'summary', consecutive_failures = 2
                """.trimIndent(),
            ).param("revision", REVISION)
            .update()

        send(HttpMethod.GET, "$CONFIG_SETS/$configSet/sync", "", alice).andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("Permission CONFIG_SET_VIEW denied") }
        }

        grant("CONFIG_SET_VIEW")
        send(HttpMethod.GET, "$CONFIG_SETS/$configSet/sync", "", alice).andExpect {
            status { isOk() }
            jsonPath("$.configSetId") { value(configSet) }
            jsonPath("$.lastSeenRevision") { value(REVISION) }
            jsonPath("$.lastSyncedRevision") { value(REVISION) }
            jsonPath("$.lastAttemptAt") { isNotEmpty() }
            jsonPath("$.lastSuccessAt") { isNotEmpty() }
            jsonPath("$.errorCode") { value("AUTH_FAILED") }
            jsonPath("$.errorSummary") { value("summary") }
            jsonPath("$.consecutiveFailures") { value(2) }
        }
    }

    @Test
    fun `an unknown ConfigSet gives 404 and an invalid ID 400`() {
        val unknown = "cfg_${"0".repeat(32)}"

        send(HttpMethod.POST, "$CONFIG_SETS/$unknown:sync", "", admin).andExpect { status { isNotFound() } }
        send(HttpMethod.GET, "$CONFIG_SETS/$unknown/sync", "", admin).andExpect { status { isNotFound() } }
        send(HttpMethod.GET, "$CONFIG_SETS/cfg_123/sync", "", admin).andExpect { status { isBadRequest() } }
    }

    private fun grant(action: String) {
        send(HttpMethod.PUT, "$CONFIG_SETS/$configSet/rules/$action", """{"subjects": ["user:alice"]}""", admin)
            .andExpect { status { isOk() } }
    }

    private fun isDue(): Boolean =
        jdbc.sql("select next_due_at <= now() from sync_state").query(Boolean::class.java).single()

    /** Creates a resource as the super admin and returns its ID. */
    private fun create(
        url: String,
        body: String,
    ): String = JsonPath.read(send(HttpMethod.POST, url, body, admin).andReturn().response.contentAsString, "$.id")

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

    companion object {
        const val NAMESPACES = "/api/v1/admin/namespaces"
        const val CONFIG_SETS = "/api/v1/admin/configsets"
        const val CREDENTIALS = "/api/v1/admin/credentials"
        val REVISION = "a".repeat(40)
    }
}
