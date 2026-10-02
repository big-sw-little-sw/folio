package io.github.big_sw_little_sw.folio.credential.web

import com.jayway.jsonpath.JsonPath
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPairs
import io.github.big_sw_little_sw.folio.credential.toCredentialId
import io.github.big_sw_little_sw.folio.credential.uniqueCredentialName
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
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
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The credential and crypto admin API end to end. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CredentialApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val keyPairs: CredentialKeyPairs,
) {
    private val admin = token(SUPER_ADMIN)
    private val alice = token("alice")

    @BeforeEach
    fun deleteAll() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from credential_key").update()
        jdbc.sql("delete from credential").update()
    }

    @Test
    fun `create returns 201 with an active key's OpenSSH public key and fingerprint`() {
        send(POST, CREDENTIALS, """{"gitInstance": "example", "name": "payments-bot"}""").andExpect {
            status { isCreated() }
            header { string(HttpHeaders.LOCATION, matchesPattern("$CREDENTIALS/cred_[0-9a-f]{32}")) }
            jsonPath("$.id") { value(matchesPattern("cred_[0-9a-f]{32}")) }
            jsonPath("$.gitInstance") { value("example") }
            jsonPath("$.name") { value("payments-bot") }
            jsonPath("$.status") { value("ENABLED") }
            jsonPath("$.keys.length()") { value(1) }
            jsonPath("$.keys[0].id") { value(matchesPattern("key_[0-9a-f]{32}")) }
            jsonPath("$.keys[0].status") { value("ACTIVE") }
            jsonPath("$.keys[0].publicKey") {
                value(matchesPattern("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5[A-Za-z0-9+/]{48} key_[0-9a-f]{32}"))
            }
            jsonPath("$.keys[0].fingerprint") { value(matchesPattern("SHA256:[A-Za-z0-9+/]{43}")) }
        }
    }

    @Test
    fun `get and list return the name, which is unique per Git instance`() {
        val id = credential("payments-bot")

        send(GET, "$CREDENTIALS/$id", "").andExpect { jsonPath("$.name") { value("payments-bot") } }
        send(GET, CREDENTIALS, "").andExpect { jsonPath("$[*].name") { value(contains("payments-bot")) } }
        send(POST, CREDENTIALS, """{"gitInstance": "example", "name": "payments-bot"}""").andExpectProblem(409)
        send(POST, CREDENTIALS, """{"gitInstance": "other", "name": "payments-bot"}""").andExpect {
            status { isCreated() }
        }
    }

    @Test
    fun `responses carry no private key material`() {
        val id = credential()
        val bodies =
            listOf(
                send(GET, "$CREDENTIALS/$id", ""),
                send(GET, CREDENTIALS, ""),
                send(POST, "$CREDENTIALS/$id:regenerate", ""),
                send(POST, "$CREDENTIALS/$id:replace", ""),
            ).map { it.andReturn().response.contentAsString }
        val pkcs8 =
            keyPairs
                .active(id.toCredentialId())
                .keyPair.private.encoded
        val seed = pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)

        bodies.forEach { body ->
            listOf("private", "cipher", "nonce", "salt").forEach { assertFalse(it in body.lowercase(), body) }
            assertFalse(Base64.getEncoder().encodeToString(seed) in body)
            assertFalse(HexFormat.of().formatHex(seed) in body)
        }
    }

    @Test
    fun `regenerate adds a pending key next to the active one, and activate retires the old key`() {
        val id = credential()
        val activeKey = keyIds(send(GET, "$CREDENTIALS/$id", "")).single()

        val pendingKey =
            keyIds(
                send(POST, "$CREDENTIALS/$id:regenerate", "").andExpect {
                    status { isOk() }
                    jsonPath("$.keys[*].status") { value(contains("ACTIVE", "PENDING")) }
                },
            ).last()

        send(POST, "$CREDENTIALS/$id:activate", """{"keyId": "$pendingKey"}""").andExpect {
            status { isOk() }
            jsonPath("$.keys[*].id") { value(contains(activeKey, pendingKey)) }
            jsonPath("$.keys[*].status") { value(contains("RETIRED", "ACTIVE")) }
        }
    }

    @Test
    fun `a second regeneration while a key is pending gives 409`() {
        val id = credential()
        send(POST, "$CREDENTIALS/$id:regenerate", "").andExpect { status { isOk() } }

        send(POST, "$CREDENTIALS/$id:regenerate", "").andExpectProblem(409)
    }

    @Test
    fun `activating a key that is not the pending key gives 409`() {
        val id = credential()
        val activeKey = keyIds(send(GET, "$CREDENTIALS/$id", "")).single()

        send(POST, "$CREDENTIALS/$id:activate", """{"keyId": "$activeKey"}""").andExpectProblem(409)
        send(POST, "$CREDENTIALS/$id:regenerate", "")
        send(POST, "$CREDENTIALS/$id:activate", """{"keyId": "$activeKey"}""").andExpectProblem(409)
    }

    @Test
    fun `discard retires the pending key, after which a new key can be generated`() {
        val id = credential()
        val pendingKey = keyIds(send(POST, "$CREDENTIALS/$id:regenerate", "")).last()

        send(POST, "$CREDENTIALS/$id:discard", """{"keyId": "$pendingKey"}""").andExpect {
            status { isOk() }
            jsonPath("$.keys[*].status") { value(contains("ACTIVE", "RETIRED")) }
            jsonPath("$.keys[1].id") { value(pendingKey) }
        }
        send(POST, "$CREDENTIALS/$id:regenerate", "").andExpect {
            status { isOk() }
            jsonPath("$.keys[*].status") { value(contains("ACTIVE", "RETIRED", "PENDING")) }
        }
    }

    @Test
    fun `discarding a key that is not the pending key gives 409`() {
        val id = credential()
        val activeKey = keyIds(send(GET, "$CREDENTIALS/$id", "")).single()

        send(POST, "$CREDENTIALS/$id:discard", """{"keyId": "$activeKey"}""").andExpectProblem(409)
        val pendingKey = keyIds(send(POST, "$CREDENTIALS/$id:regenerate", "")).last()
        send(POST, "$CREDENTIALS/$id:discard", """{"keyId": "$pendingKey"}""").andExpect { status { isOk() } }
        send(POST, "$CREDENTIALS/$id:discard", """{"keyId": "$pendingKey"}""").andExpectProblem(409)
    }

    @Test
    fun `emergency replacement activates a new key at once and retires the active and pending keys`() {
        val id = credential()
        send(POST, "$CREDENTIALS/$id:regenerate", "")

        send(POST, "$CREDENTIALS/$id:replace", "").andExpect {
            status { isOk() }
            jsonPath("$.keys[*].status") { value(contains("RETIRED", "RETIRED", "ACTIVE")) }
        }
    }

    @Test
    fun `a disabled credential refuses key changes`() {
        val id = credential()
        val pendingKey = keyIds(send(POST, "$CREDENTIALS/$id:regenerate", "")).last()

        send(POST, "$CREDENTIALS/$id:disable", "").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DISABLED") }
        }
        send(POST, "$CREDENTIALS/$id:disable", "").andExpect { status { isOk() } }
        send(POST, "$CREDENTIALS/$id:regenerate", "").andExpectProblem(409)
        send(POST, "$CREDENTIALS/$id:replace", "").andExpectProblem(409)
        send(POST, "$CREDENTIALS/$id:activate", """{"keyId": "$pendingKey"}""").andExpectProblem(409)
        send(POST, "$CREDENTIALS/$id:discard", """{"keyId": "$pendingKey"}""").andExpectProblem(409)
    }

    @Test
    fun `a non-admin gets 403 on a disabled credential, not 409`() {
        val id = credential()
        send(POST, "$CREDENTIALS/$id:disable", "").andExpect { status { isOk() } }

        send(POST, "$CREDENTIALS/$id:regenerate", "", alice).andExpectProblem(403)
        send(POST, "$CREDENTIALS/$id:replace", "", alice).andExpectProblem(403)
        send(POST, "$CREDENTIALS/$id:disable", "", alice).andExpectProblem(403)
    }

    @Test
    fun `an unknown Git instance, invalid names and IDs and missing fields give 400`() {
        val id = credential()

        send(POST, CREDENTIALS, """{"gitInstance": "unknown", "name": "bot"}""").andExpectProblem(400)
        send(POST, CREDENTIALS, """{"gitInstance": "example", "name": "Payments Bot"}""").andExpectProblem(400)
        send(POST, CREDENTIALS, """{"gitInstance": "example"}""").andExpectProblem(400)
        send(POST, CREDENTIALS, "{}").andExpectProblem(400)
        send(GET, "$CREDENTIALS/cred_123", "").andExpectProblem(400)
        send(GET, "$CREDENTIALS/${id.replace("cred_", "key_")}", "").andExpectProblem(400)
        send(POST, "$CREDENTIALS/$id:activate", """{"keyId": "$id"}""").andExpectProblem(400)
    }

    @Test
    fun `a missing credential gives 404 whoever asks`() {
        val missing = "cred_${"0".repeat(32)}"

        send(GET, "$CREDENTIALS/$missing", "").andExpectProblem(404)
        send(GET, "$CREDENTIALS/$missing", "", alice).andExpectProblem(404)
        send(POST, "$CREDENTIALS/$missing:disable", "").andExpectProblem(404)
    }

    @Test
    fun `without super admin rights every endpoint denies with 403`() {
        val id = credential()
        val keyId = keyIds(send(GET, "$CREDENTIALS/$id", "")).single()

        endpoints(id, keyId).forEach { (method, url, body) -> send(method, url, body, alice).andExpectProblem(403) }
    }

    @Test
    fun `requests without a token are rejected with 401 on every endpoint`() {
        val id = credential()
        val keyId = keyIds(send(GET, "$CREDENTIALS/$id", "")).single()

        endpoints(id, keyId).forEach { (method, url, body) ->
            mvc
                .request(method, url) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect {
                    status { isUnauthorized() }
                    header { string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")) }
                }
        }
    }

    @Test
    fun `crypto usage counts stored keys per configured master-key version`() {
        val id = credential()
        send(POST, "$CREDENTIALS/$id:regenerate", "")
        credential()

        listOf(send(GET, CRYPTO, ""), send(POST, "$CRYPTO:reencrypt", "")).forEach {
            it.andExpect {
                status { isOk() }
                jsonPath("$.activeVersion") { value(2) }
                jsonPath("$.versions[*].version") { value(contains(1, 2)) }
                jsonPath("$.versions[*].keys") { value(contains(0, 3)) }
            }
        }
    }

    private fun endpoints(
        id: String,
        keyId: String,
    ) = listOf(
        Triple(POST, CREDENTIALS, """{"gitInstance": "example", "name": "other"}"""),
        Triple(GET, CREDENTIALS, ""),
        Triple(GET, "$CREDENTIALS/$id", ""),
        Triple(POST, "$CREDENTIALS/$id:regenerate", ""),
        Triple(POST, "$CREDENTIALS/$id:activate", """{"keyId": "$keyId"}"""),
        Triple(POST, "$CREDENTIALS/$id:discard", """{"keyId": "$keyId"}"""),
        Triple(POST, "$CREDENTIALS/$id:replace", ""),
        Triple(POST, "$CREDENTIALS/$id:disable", ""),
        Triple(GET, CRYPTO, ""),
        Triple(POST, "$CRYPTO:reencrypt", ""),
    )

    private fun credential(name: String = uniqueCredentialName().value): String {
        val body =
            send(POST, CREDENTIALS, """{"gitInstance": "example", "name": "$name"}""")
                .andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.id")
    }

    private fun keyIds(result: ResultActionsDsl): List<String> =
        JsonPath.read(result.andReturn().response.contentAsString, "$.keys[*].id")

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

    private fun token(subject: String): RequestPostProcessor = jwt().jwt { it.subject(subject) }

    private companion object {
        const val CREDENTIALS = "/api/v1/admin/credentials"
        const val CRYPTO = "/api/v1/admin/crypto"
    }
}
