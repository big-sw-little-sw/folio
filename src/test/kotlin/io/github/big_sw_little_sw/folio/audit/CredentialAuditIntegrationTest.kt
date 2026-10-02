package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialKey
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.credential.KeyNotPendingException
import io.github.big_sw_little_sw.folio.credential.KeyStatus
import io.github.big_sw_little_sw.folio.credential.uniqueCredentialName
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Audit records of credential and key operations (ADR 0038): key IDs and fingerprints, never key material. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CredentialAuditIntegrationTest(
    @Autowired private val credentials: CredentialService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val records = AuditRecords(jdbc)

    @BeforeEach
    fun authenticate() {
        authenticateAs(SUPER_ADMIN)
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `each credential operation writes one record with the keys whose status it set`() {
        val created = credentials.create("example", uniqueCredentialName())
        val first = created.key(KeyStatus.ACTIVE)
        val second = credentials.regenerate(created.id).key(KeyStatus.PENDING)
        credentials.activate(created.id, second.id)
        val third = credentials.regenerate(created.id).key(KeyStatus.PENDING)
        credentials.discard(created.id, third.id)
        val fourth = credentials.replace(created.id).key(KeyStatus.ACTIVE)
        credentials.disable(created.id)
        credentials.disable(created.id)

        val rows = records.of(created.id.value)
        assertEquals(
            listOf(
                "CREDENTIAL_CREATED" to listOf(keyDetails(first, "ACTIVE")),
                "CREDENTIAL_KEY_REGENERATED" to listOf(keyDetails(second, "PENDING")),
                "CREDENTIAL_KEY_ACTIVATED" to listOf(keyDetails(first, "RETIRED"), keyDetails(second, "ACTIVE")),
                "CREDENTIAL_KEY_REGENERATED" to listOf(keyDetails(third, "PENDING")),
                "CREDENTIAL_KEY_DISCARDED" to listOf(keyDetails(third, "RETIRED")),
                "CREDENTIAL_KEY_REPLACED" to listOf(keyDetails(second, "RETIRED"), keyDetails(fourth, "ACTIVE")),
                "CREDENTIAL_DISABLED" to emptyList(),
            ),
            rows.map { it.action to it.details["keys"] },
        )
        assertEquals(
            setOf(listOf("CREDENTIAL", null, "example", created.name.value)),
            rows.map { listOf(it.resourceType, it.path, it.details["gitInstance"], it.details["name"]) }.toSet(),
        )
    }

    @Test
    fun `records hold no public key, ciphertext or other key material`() {
        val created = credentials.create("example", uniqueCredentialName())
        credentials.replace(created.id)
        val ciphertexts =
            jdbc
                .sql(
                    "select ciphertext, salt, nonce from credential_key " +
                        "where credential_id = :id and ciphertext is not null",
                ).param("id", created.id.value)
                .query { rs, _ -> listOf(rs.getBytes(1), rs.getBytes(2), rs.getBytes(3)) }
                .list()
                .flatten()
                .map { Base64.getEncoder().encodeToString(it) }
        val stored =
            jdbc
                .sql("select details::text from audit_event where resource_id = :id")
                .param("id", created.id.value)
                .query(String::class.java)
                .list()
                .joinToString()

        assertFalse("ssh-ed25519" in stored, stored)
        assertFalse(credentials.get(created.id).keys.any { it.publicKey.substringAfter(' ') in stored })
        ciphertexts.forEach { assertFalse(it in stored) }
    }

    @Test
    fun `a failed key operation writes nothing`() {
        val created = credentials.create("example", uniqueCredentialName())

        assertFailsWith<KeyNotPendingException> {
            credentials.activate(created.id, created.key(KeyStatus.ACTIVE).id)
        }

        assertEquals(listOf("CREDENTIAL_CREATED"), records.of(created.id.value).map { it.action })
    }

    @Test
    fun `a key operation whose audit record cannot be written fails and changes nothing`() {
        val created = credentials.create("example", uniqueCredentialName())
        // NOT VALID: records of earlier tests are not checked, only new ones.
        jdbc
            .sql(
                "alter table audit_event add constraint audit_test_failure " +
                    "check (action <> 'CREDENTIAL_KEY_REPLACED') not valid",
            ).update()
        try {
            assertFailsWith<DataIntegrityViolationException> { credentials.replace(created.id) }
        } finally {
            jdbc.sql("alter table audit_event drop constraint audit_test_failure").update()
        }

        assertEquals(created.keys, credentials.get(created.id).keys)
        assertEquals(listOf("CREDENTIAL_CREATED"), records.of(created.id.value).map { it.action })
    }

    private fun Credential.key(status: KeyStatus): CredentialKey = keys.single { it.status == status }

    private fun keyDetails(
        key: CredentialKey,
        status: String,
    ) = mapOf("keyId" to key.id.value.toString(), "fingerprint" to key.fingerprint, "status" to status)
}
