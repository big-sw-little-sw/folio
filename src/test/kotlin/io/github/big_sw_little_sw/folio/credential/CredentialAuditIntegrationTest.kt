package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestAudit
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.andExpectForbidden
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.json
import io.github.big_sw_little_sw.testing.send
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These tests check the audit records of the credential module (design 14) through the HTTP routes. Test classes share
 * the database, so each test reads only the records of its own credentials.
 */
@FolioIntegrationTest
class CredentialAuditIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val audit: TestAudit,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), "cli")

    @Test
    fun `a create records the credential, the Namespace path and the first key`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")

        val created = create(namespace, alice, "deploy")

        val record = records(created).single()
        assertEquals("CREDENTIAL_CREATED", record["operation"])
        assertEquals("CREDENTIAL", record["resource_type"])
        assertEquals(catalog.pathOf(namespace), record["path"])
        assertEquals("alice", record["actor_subject"])
        assertEquals(false, record["actor_super_admin"])
        assertEquals(
            audit.json(
                """
                {"namespaceId": "${namespace.toApiId()}", "name": "deploy", "gitInstance": "github",
                 "keys": [${change(created, 0, "ACTIVE", null)}]}
                """,
            ),
            audit.json(record["details"]),
        )
    }

    @Test
    fun `each key change records the keys whose status changed`() {
        val created = create(catalog.namespace())
        val id = created.json("$.id")
        val regenerated = post("$CREDENTIALS/$id:regenerate")
        val activated = post("$CREDENTIALS/$id:activate", """{"keyId": "${regenerated.json("$.keys[1].id")}"}""")
        val regeneratedAgain = post("$CREDENTIALS/$id:regenerate")
        val discarded = post("$CREDENTIALS/$id:discard", """{"keyId": "${regeneratedAgain.json("$.keys[2].id")}"}""")
        val replaced = post("$CREDENTIALS/$id:replace")

        val records = records(created)
        val operations = listOf("CREATED", "REGENERATED", "ACTIVATED", "REGENERATED", "DISCARDED", "REPLACED")
        assertEquals(operations.map { "CREDENTIAL_$it" }, records.map { it["operation"] })
        assertEquals(listOf(change(regenerated, 1, "PENDING", null)), keys(records[1]))
        assertEquals(
            listOf(change(activated, 0, "RETIRED", "ACTIVE"), change(activated, 1, "ACTIVE", "PENDING")),
            keys(records[2]),
        )
        assertEquals(listOf(change(discarded, 2, "RETIRED", "PENDING")), keys(records[4]))
        assertEquals(
            listOf(change(replaced, 1, "RETIRED", "ACTIVE"), change(replaced, 3, "ACTIVE", null)),
            keys(records[5]),
        )
        records.forEach { record ->
            assertTrue(listOf("ciphertext", "salt", "nonce", "ssh-ed25519").none { it in record["details"].toString() })
        }
    }

    @Test
    fun `a disable records once, and a delete records the credential`() {
        val namespace = catalog.namespace()
        val created = create(namespace, name = "deploy")
        val id = created.json("$.id")

        post("$CREDENTIALS/$id:disable")
        post("$CREDENTIALS/$id:disable")
        mvc.delete("$CREDENTIALS/$id") { bearer(SUPER_ADMIN) }.andExpect { status { isNoContent() } }

        val records = records(created)
        assertEquals(
            listOf("CREDENTIAL_CREATED", "CREDENTIAL_DISABLED", "CREDENTIAL_DELETED"),
            records.map { it["operation"] },
        )
        assertEquals(audit.json("{}"), audit.json(records[1]["details"]))
        assertEquals(
            audit.json("""{"namespaceId": "${namespace.toApiId()}", "name": "deploy", "gitInstance": "github"}"""),
            audit.json(records[2]["details"]),
        )
        assertEquals(catalog.pathOf(namespace), records[2]["path"])
    }

    @Test
    fun `a conflict or a denial writes no record`() {
        val namespace = catalog.namespace()
        val created = create(namespace)
        val id = created.json("$.id")
        post("$CREDENTIALS/$id:regenerate")

        post("$CREDENTIALS/$id:regenerate").andExpectProblem(409, "PENDING_KEY_EXISTS")
        post("$CREDENTIALS/$id:activate", """{"keyId": "key_${"0".repeat(32)}"}""")
            .andExpectProblem(409, "KEY_NOT_PENDING")
        mvc.post("$CREDENTIALS/$id:replace") { bearer(alice) }.andExpectForbidden(Action.MANAGE, namespace.toApiId())

        assertEquals(
            listOf("CREDENTIAL_CREATED", "CREDENTIAL_REGENERATED"),
            records(created).map { it["operation"] },
        )
    }

    private fun create(
        namespace: NamespaceId,
        caller: Caller.Authenticated = SUPER_ADMIN,
        name: String = TestCatalog.uniqueSlug("cred"),
    ): ResultActionsDsl = mvc
        .send(
            POST,
            CREDENTIALS,
            caller,
            """{"namespaceId": "${namespace.toApiId()}", "name": "$name", "gitInstance": "github"}""",
        )
        .andExpect { status { isCreated() } }

    private fun post(url: String, body: String? = null): ResultActionsDsl = mvc.send(POST, url, SUPER_ADMIN, body)

    /** The expected details entry of the key at [index] of a credential response. */
    private fun change(response: ResultActionsDsl, index: Int, status: String, previous: String?): JsonNode =
        audit.json(
            """
            {"keyId": "${response.json("$.keys[$index].id")}",
             "fingerprint": "${response.json("$.keys[$index].fingerprint")}", "status": "$status",
             "previousStatus": ${previous?.let { "\"$it\"" }}}
            """,
        )

    private fun keys(record: Map<String, Any?>): List<JsonNode> = audit.json(record["details"])["keys"].toList()

    private fun records(created: ResultActionsDsl): List<Map<String, Any?>> =
        audit.records(CREDENTIAL_ID.parse(created.json("$.id")).value)

    private companion object {
        const val CREDENTIALS = "/api/v1/admin/credentials"
    }
}
