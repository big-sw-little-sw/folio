package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.*
import java.util.UUID
import kotlin.test.*

@FolioIntegrationTest
class CredentialApiIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)
    private val bob = Caller.Authenticated("bob", setOf("team-b"), null)

    @Test
    fun `create generates the first key and activates it`() {
        val namespace = catalog.namespace()

        val created = create(namespace, "deploy", "github-internal")

        created.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(startsWith("cred_")) }
            jsonPath("$.namespaceId") { value(namespace.toApiId()) }
            jsonPath("$.namespacePath") { value(catalog.pathOf(namespace)) }
            jsonPath("$.name") { value("deploy") }
            jsonPath("$.gitInstance") { value("github-internal") }
            jsonPath("$.status") { value("ENABLED") }
            jsonPath("$.keys") { value(hasSize<Any>(1)) }
            jsonPath("$.keys[0].id") { value(startsWith("key_")) }
            jsonPath("$.keys[0].status") { value("ACTIVE") }
            jsonPath("$.keys[0].fingerprint") { value(matchesPattern("SHA256:[A-Za-z0-9+/]{43}")) }
            jsonPath("$.keys[0].publicKey") {
                value(matchesPattern("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI[A-Za-z0-9+/]{43} key_[0-9a-f]{32}"))
            }
        }
        val id = created.json("$.id")
        created.andExpect {
            header { string(HttpHeaders.LOCATION, "$CREDENTIALS/$id") }
            jsonPath("$.keys[0].publicKey") { value(endsWith(" ${created.json("$.keys[0].id")}")) }
        }
        val body = created.andReturn().response.contentAsString
        listOf("ciphertext", "salt", "nonce", "PRIVATE", "privateKey").forEach { assertFalse(it in body, it) }
    }

    @Test
    fun `get and list need MANAGE on the Namespace that owns the credential`() {
        val namespace = catalog.namespace()
        val id = create(namespace, "deploy").json("$.id")
        create(namespace, "another")
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        catalog.rule(namespace, Action.READ, "user:bob")

        mvc.get("$CREDENTIALS/$id") { bearer(alice) }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("deploy") }
        }
        mvc.get("$CREDENTIALS?namespaceId=${namespace.toApiId()}") { bearer(alice) }.andExpect {
            status { isOk() }
            jsonPath("$.credentials[*].name") { value(contains("another", "deploy")) }
        }
        mvc.get("$CREDENTIALS/$id") { bearer(bob) }.andExpectForbidden(Action.MANAGE, namespace.toApiId())
        mvc
            .get("$CREDENTIALS?namespaceId=${namespace.toApiId()}") { bearer(bob) }
            .andExpectForbidden(Action.MANAGE, namespace.toApiId())
    }

    @Test
    fun `a caller with MANAGE on a parent Namespace manages the credentials below it`() {
        val top = catalog.namespace()
        val team = catalog.namespace(top)
        catalog.rule(top, Action.MANAGE, "group:team-a")

        create(team, "deploy", caller = alice).andExpect { status { isCreated() } }
        create(team, "other", caller = bob).andExpectForbidden(Action.MANAGE, team.toApiId())
    }

    @Test
    fun `create rejects requests that are not valid`() {
        val namespace = catalog.namespace()

        val id = namespace.toApiId()
        listOf(
            """{"namespaceId": "$id", "name": "Deploy", "gitInstance": "github"}""" to "INVALID_SLUG",
            """{"namespaceId": "$id", "name": "deploy", "gitInstance": "git hub"}""" to "INVALID_SLUG",
            """{"namespaceId": "ns_1", "name": "deploy", "gitInstance": "github"}""" to "INVALID_ID",
            """{"namespaceId": "$id", "name": "deploy"}""" to "INVALID_REQUEST",
        ).forEach { (body, code) ->
            mvc.send(POST, CREDENTIALS, SUPER_ADMIN, body).andExpectProblem(400, code)
        }
        create(NamespaceId(UUID.randomUUID()), "deploy").andExpectProblem(404, "NAMESPACE_NOT_FOUND")
        get("cred_${"0".repeat(32)}").andExpectProblem(404, "CREDENTIAL_NOT_FOUND")
    }

    @Test
    fun `a name is unique in its Namespace`() {
        val namespace = catalog.namespace()
        create(namespace, "deploy").andExpect { status { isCreated() } }

        create(namespace, "deploy", "other-instance").andExpectProblem(409, "CREDENTIAL_NAME_TAKEN")
        create(catalog.namespace(), "deploy").andExpect { status { isCreated() } }
    }

    @Test
    fun `rotation adds a pending key, activates it and retires the previous key`() {
        val id = create(catalog.namespace(), "deploy").json("$.id")
        val first = get(id).json("$.keys[0].id")

        val regenerated = post("$CREDENTIALS/$id:regenerate")
        regenerated.andExpect {
            status { isOk() }
            jsonPath("$.keys[0].status") { value("ACTIVE") }
            jsonPath("$.keys[1].status") { value("PENDING") }
        }
        val pending = regenerated.json("$.keys[1].id")
        post("$CREDENTIALS/$id:regenerate").andExpectProblem(409, "PENDING_KEY_EXISTS")
        post("$CREDENTIALS/$id:activate", """{"keyId": "$first"}""").andExpectProblem(409, "KEY_NOT_PENDING")

        post("$CREDENTIALS/$id:activate", """{"keyId": "$pending"}""").andExpect {
            status { isOk() }
            jsonPath("$.keys[0].id") { value(first) }
            jsonPath("$.keys[0].status") { value("RETIRED") }
            jsonPath("$.keys[0].publicKey") { value(startsWith("ssh-ed25519 ")) }
            jsonPath("$.keys[0].fingerprint") { value(startsWith("SHA256:")) }
            jsonPath("$.keys[1].status") { value("ACTIVE") }
        }
        post("$CREDENTIALS/$id:activate", """{"keyId": "$pending"}""").andExpectProblem(409, "KEY_NOT_PENDING")
    }

    @Test
    fun `discard retires the pending key and keeps the active key`() {
        val id = create(catalog.namespace(), "deploy").json("$.id")
        val pending = post("$CREDENTIALS/$id:regenerate").json("$.keys[1].id")

        post(
            "$CREDENTIALS/$id:discard",
            """{"keyId": "key_${"0".repeat(32)}"}""",
        ).andExpectProblem(409, "KEY_NOT_PENDING")
        post("$CREDENTIALS/$id:discard", """{"keyId": "$pending"}""").andExpect {
            status { isOk() }
            jsonPath("$.keys[0].status") { value("ACTIVE") }
            jsonPath("$.keys[1].status") { value("RETIRED") }
        }
        post("$CREDENTIALS/$id:regenerate").andExpect { status { isOk() } }
        post("$CREDENTIALS/$id:discard", """{"keyId": "key_1"}""").andExpectProblem(400, "INVALID_ID")
    }

    @Test
    fun `replace retires the active and the pending key and activates a new key`() {
        val id = create(catalog.namespace(), "deploy").json("$.id")
        post("$CREDENTIALS/$id:regenerate")

        post("$CREDENTIALS/$id:replace").andExpect {
            status { isOk() }
            jsonPath("$.keys[*].status") { value(contains("RETIRED", "RETIRED", "ACTIVE")) }
        }
        post("$CREDENTIALS/$id:replace").andExpect {
            jsonPath("$.keys[*].status") {
                value(contains("RETIRED", "RETIRED", "RETIRED", "ACTIVE"))
            }
        }
    }

    @Test
    fun `disable is permanent and a disabled credential refuses key changes`() {
        val id = create(catalog.namespace(), "deploy").json("$.id")

        post("$CREDENTIALS/$id:disable").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DISABLED") }
            jsonPath("$.keys[0].status") { value("ACTIVE") }
        }
        post("$CREDENTIALS/$id:disable").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DISABLED") }
        }
        val active = get(id).json("$.keys[0].id")
        post("$CREDENTIALS/$id:regenerate").andExpectProblem(409, "CREDENTIAL_DISABLED")
        post("$CREDENTIALS/$id:replace").andExpectProblem(409, "CREDENTIAL_DISABLED")
        post("$CREDENTIALS/$id:activate", """{"keyId": "$active"}""").andExpectProblem(409, "CREDENTIAL_DISABLED")
        post("$CREDENTIALS/$id:discard", """{"keyId": "$active"}""").andExpectProblem(409, "CREDENTIAL_DISABLED")
    }

    @Test
    fun `delete removes the credential and its keys, also a disabled credential`() {
        val namespace = catalog.namespace()
        val id = create(namespace, "deploy").json("$.id")
        post("$CREDENTIALS/$id:disable")
        catalog.rule(namespace, Action.MANAGE, "user:alice")

        mvc.delete("$CREDENTIALS/$id") { bearer(bob) }.andExpectForbidden(Action.MANAGE, namespace.toApiId())
        mvc.delete("$CREDENTIALS/$id") { bearer(alice) }.andExpect { status { isNoContent() } }
        get(id).andExpectProblem(404, "CREDENTIAL_NOT_FOUND")
        mvc.delete("$CREDENTIALS/$id") { bearer(alice) }.andExpectProblem(404, "CREDENTIAL_NOT_FOUND")
        create(namespace, "deploy").andExpect { status { isCreated() } }
    }

    @Test
    fun `key changes need MANAGE, and authorization comes before the state`() {
        val namespace = catalog.namespace()
        val id = create(namespace, "deploy").json("$.id")
        post("$CREDENTIALS/$id:disable")
        catalog.rule(namespace, Action.READ, "user:bob")
        val key = """{"keyId": "${get(id).json("$.keys[0].id")}"}"""

        listOf(":regenerate" to null, ":replace" to null, ":disable" to null, ":activate" to key, ":discard" to key)
            .forEach { (operation, body) ->
                mvc
                    .send(POST, "$CREDENTIALS/$id$operation", bob, body)
                    .andExpectForbidden(Action.MANAGE, namespace.toApiId())
            }
    }

    /**
     * Folio finds the resource first, so a caller without permission and an anonymous caller also get 404 for a missing
     * ID. Nobody can guess an ID, so the 404 does not give away a resource.
     */
    @Test
    fun `not found comes before authorization`() {
        val namespace = NamespaceId(UUID.randomUUID())
        val credential = CredentialId(UUID.randomUUID()).toApiId()
        listOf(bob, null).forEach { caller ->
            create(namespace, "deploy", caller = caller).andExpectProblem(404, "NAMESPACE_NOT_FOUND")
            mvc.send(GET, "$CREDENTIALS?namespaceId=${namespace.toApiId()}", caller)
                .andExpectProblem(404, "NAMESPACE_NOT_FOUND")
            mvc
                .send(POST, "$CREDENTIALS/$credential:regenerate", caller)
                .andExpectProblem(404, "CREDENTIAL_NOT_FOUND")
        }
    }

    @Test
    fun `a Namespace with a credential is not empty`() {
        val namespace = catalog.namespace()
        val id = create(namespace, "deploy").json("$.id")

        mvc
            .delete("/api/v1/admin/namespaces/${namespace.toApiId()}") { bearer(SUPER_ADMIN) }
            .andExpectProblem(409, "NAMESPACE_NOT_EMPTY")
        mvc.delete("$CREDENTIALS/$id") { bearer(SUPER_ADMIN) }.andExpect { status { isNoContent() } }
        mvc
            .delete("/api/v1/admin/namespaces/${namespace.toApiId()}") { bearer(SUPER_ADMIN) }
            .andExpect { status { isNoContent() } }
    }

    private fun create(
        namespace: NamespaceId,
        name: String,
        gitInstance: String = "github",
        caller: Caller.Authenticated? = SUPER_ADMIN,
    ): ResultActionsDsl {
        val body = """{"namespaceId": "${namespace.toApiId()}", "name": "$name", "gitInstance": "$gitInstance"}"""
        return mvc.send(POST, CREDENTIALS, caller, body)
    }

    private fun get(id: String) = mvc.get("$CREDENTIALS/$id") { bearer(SUPER_ADMIN) }

    private fun post(url: String, body: String? = null) = mvc.send(POST, url, SUPER_ADMIN, body)

    private companion object {
        const val CREDENTIALS = "/api/v1/admin/credentials"
    }
}
