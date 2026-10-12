package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.FolioApplication
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.credential.internal.CredentialService
import io.github.big_sw_little_sw.folio.credential.internal.CryptoProperties
import io.github.big_sw_little_sw.folio.credential.internal.KeyRepository
import io.github.big_sw_little_sw.folio.credential.internal.MasterKeyRing
import io.github.big_sw_little_sw.folio.credential.internal.MasterKeyService
import io.github.big_sw_little_sw.folio.credential.internal.PrivateKeyCipher
import io.github.big_sw_little_sw.folio.credential.internal.Reencryption
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.*
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.*

/**
 * These tests check the master-key ring and the re-encryption against the database. The test ring has the versions 1
 * and 2, and version 2 is active. Test classes share the database, so each test first moves all keys to version 2 and
 * then stores only its own keys with version 1.
 */
@FolioIntegrationTest
class CryptoIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val fixture: TestCredentials,
    @Autowired private val audit: TestAudit,
    @Autowired private val locks: TestLocks,
    @Autowired private val credentials: CredentialService,
    @Autowired private val keys: CredentialKeys,
    @Autowired private val masterKeys: MasterKeyService,
    @Autowired private val keyRows: KeyRepository,
    @Autowired private val cipher: PrivateKeyCipher,
    @Autowired private val properties: CryptoProperties,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val postgres: PostgreSQLContainer,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    /** This cipher has version 1 as its active version. It encrypts keys as Folio did before version 2 was active. */
    private val previousCipher by lazy { PrivateKeyCipher(MasterKeyRing(properties.copy(activeKeyVersion = 1))) }

    @Test
    fun `only a super admin sees the status and runs the re-encryption`() {
        catalog.rule(catalog.namespace(), Action.MANAGE, "user:alice")

        mvc.get(CRYPTO) { bearer(alice) }.andExpectForbidden(Action.MANAGE, null)
        mvc.post("$CRYPTO:reencrypt") { bearer(alice) }.andExpectForbidden(Action.MANAGE, null)
        mvc.get(CRYPTO) { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.activeVersion") { value(2) }
            jsonPath("$.versions[*].version") { value(contains(1, 2)) }
        }
    }

    @Test
    fun `the re-encryption moves keys to the active version, and the keys still work`() {
        reencryptAll()
        val first = create()
        fixture.pendingKey(first)
        val second = create()
        storeUnderVersion1(listOf(first, second))
        mvc.get(CRYPTO) { bearer(SUPER_ADMIN) }.andExpect { jsonPath("$.versions[0].keys") { value(3) } }

        mvc.post("$CRYPTO:reencrypt") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.reencrypted[0].version") { value(1) }
            jsonPath("$.reencrypted[0].keys") { value(3) }
            jsonPath("$.activeVersion") { value(2) }
            jsonPath("$.versions[0].version") { value(1) }
            jsonPath("$.versions[0].keys") { value(0) }
        }

        assertEquals(setOf(2), versionsOf(listOf(first, second)))
        listOf(first, second).forEach { assertTrue(signs(it)) }
    }

    @Test
    fun `a run writes one audit record with the counts for each version`() {
        reencryptAll()
        val credential = create()
        storeUnderVersion1(listOf(credential))

        reencryptAll()

        val record = audit.records(REENCRYPTED).last()
        assertEquals("MASTER_KEY_RING", record["resource_type"])
        assertEquals(null, record["resource_id"])
        assertEquals(null, record["path"])
        assertEquals("root", record["actor_subject"])
        assertEquals(true, record["actor_super_admin"])
        audit.assertDetails("""{"activeVersion": 2, "reencrypted": {"1": 1}, "complete": true}""", record)
    }

    @Test
    fun `a repeated run changes nothing and writes no audit record`() {
        val credential = create()
        storeUnderVersion1(listOf(credential))
        reencryptAll()
        val ciphertext = ciphertextOf(credential)
        val records = audit.records(REENCRYPTED).size

        mvc.post("$CRYPTO:reencrypt") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.reencrypted") { isEmpty() }
        }

        assertTrue(ciphertext.contentEquals(ciphertextOf(credential)))
        assertEquals(records, audit.records(REENCRYPTED).size)
    }

    /**
     * A key that does not decrypt stops the run. Folio does not promise an order of the keys, so the test accepts a run
     * that moved the other key first and a run that did not.
     */
    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `a run that fails names the key in the log and records the keys that it moved`(output: CapturedOutput) {
        reencryptAll()
        val other = create()
        val broken = fixture.credential()
        storeUnderVersion1(listOf(other, broken.id))
        corrupt(broken.id)
        val before = audit.records(REENCRYPTED).size
        try {
            mvc.post("$CRYPTO:reencrypt") { bearer(SUPER_ADMIN) }.andExpectProblem(500, "INTERNAL")

            val brokenKey = broken.keys.single().id
            assertTrue(
                "Key ${brokenKey.toApiId()} of credential ${broken.id.toApiId()} under master-key version 1 does not " +
                    "decrypt." in output.out,
            )
            val moved = if (versionsOf(listOf(other)) == setOf(2)) 1 else 0
            val records = audit.records(REENCRYPTED).drop(before)
            if (moved == 0) {
                assertEquals(emptyList(), records)
            } else {
                audit.assertDetails(
                    """{"activeVersion": 2, "reencrypted": {"1": 1}, "complete": false}""",
                    records.single(),
                )
            }
        } finally {
            fixture.delete(broken.id)
        }
    }

    @Test
    fun `parallel runs are safe`() {
        reencryptAll()
        val ids = (1..5).map { create() }
        storeUnderVersion1(ids)
        val runs = 4
        val barrier = CyclicBarrier(runs)

        val moved =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                (1..runs)
                    .map {
                        executor.submit<Int> {
                            barrier.await()
                            masterKeys
                                .reencrypt(SUPER_ADMIN)
                                .reencrypted.values
                                .sum()
                        }
                    }.map { it.await() }
            }

        assertEquals(5, moved.sum(), "Each key moves one time")
        assertEquals(setOf(2), versionsOf(ids))
        ids.forEach { assertTrue(signs(it)) }
    }

    /**
     * The holder retires the key, and the run reads the key before the retirement commits. The run then waits for the
     * row. After the commit, the version that the run read no longer encrypts the row, so the run changes nothing.
     */
    @Test
    fun `the re-encryption cannot bring back a key that was retired after the read`() {
        reencryptAll()
        val credential = fixture.credential()
        storeUnderVersion1(listOf(credential.id))

        val results =
            locks.whileHeld(
                { credentials.replace(SUPER_ADMIN, credential.id) },
                { masterKeys.reencrypt(SUPER_ADMIN) },
            )

        val run = results.single().getOrThrow() as Reencryption
        assertEquals(emptyMap(), run.reencrypted)
        val retired = credential.keys.single().id
        val erased =
            jdbc
                .sql(
                    """
                    select num_nulls(algorithm, master_key_version, salt, nonce, ciphertext)
                    from credential_key where id = :id
                    """.trimIndent(),
                ).param("id", retired.value)
                .query(Int::class.java)
                .single()
        assertEquals(5, erased, "The retired key has no key material")
    }

    @Test
    fun `Folio does not start if a stored key uses a version that the configuration does not have`() {
        val credential = create()
        jdbc
            .sql("update credential_key set master_key_version = 99 where credential_id = :id")
            .param("id", credential.value)
            .update()
        try {
            val failure = assertFailsWith<Exception> { startApplication().close() }

            val messages = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "master-key versions [99]" in it }, messages.toString())
            properties.masterKeys.values.forEach { secret -> assertTrue(messages.none { secret in it }) }
        } finally {
            fixture.delete(credential)
        }
    }

    private fun create(): CredentialId = fixture.credential().id

    /** Moves each key of the database to the active version, so that a test counts only its own keys. */
    private fun reencryptAll() {
        mvc.post("$CRYPTO:reencrypt") { bearer(SUPER_ADMIN) }.andExpect { status { isOk() } }
    }

    /** Stores the keys of [ids] as Folio stored them while version 1 was active. */
    private fun storeUnderVersion1(ids: List<CredentialId>) {
        keyRows.notUnder(1).filter { it.credentialId in ids }.forEach { key ->
            val encrypted =
                cipher.decrypt(key.encrypted, key.credentialId, key.id) {
                    previousCipher.encrypt(it, key.credentialId, key.id)
                }
            check(keyRows.replaceEncryption(key.id, key.encrypted.masterKeyVersion, encrypted))
        }
    }

    /** Changes one byte of the ciphertext, so the authentication tag no longer matches. */
    private fun corrupt(id: CredentialId) {
        jdbc
            .sql(
                """
                update credential_key set ciphertext = set_byte(ciphertext, 0, (get_byte(ciphertext, 0) + 1) % 256)
                where credential_id = :id
                """.trimIndent(),
            ).param("id", id.value)
            .update()
    }

    private fun versionsOf(ids: List<CredentialId>): Set<Int> = jdbc
        .sql(
            """
                select distinct master_key_version from credential_key
                where credential_id = any(cast(:ids as uuid[])) and master_key_version is not null
            """.trimIndent(),
        ).param("ids", ids.map { it.value }.toTypedArray())
        .query(Int::class.java)
        .set()
        .filterNotNull()
        .toSet()

    private fun ciphertextOf(id: CredentialId): ByteArray = jdbc
        .sql("select ciphertext from credential_key where credential_id = :id and status = 'ACTIVE'")
        .param("id", id.value)
        .query(ByteArray::class.java)
        .single()

    private fun signs(id: CredentialId): Boolean =
        keys.withActiveKey(id) { key -> signsFor(key.keyPair.private, key.keyPair.public) }

    /**
     * Starts a second Folio on the database of this test, as a deployment does at startup. Command-line arguments take
     * precedence over the test configuration file.
     */
    private fun startApplication(): ConfigurableApplicationContext =
        SpringApplicationBuilder(FolioApplication::class.java)
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> {
                    it.beanFactory.registerSingleton("excludeTestConfigurations", ExcludeTestConfigurations())
                },
            ).run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=${TestIssuer.uri}",
                "--spring.security.oauth2.client.provider.folio.issuer-uri=${TestIssuer.uri}",
                "--server.port=0",
                "--management.server.port=0",
            )

    /**
     * The second application scans the application package, which also has the test configurations. Without this
     * filter, the second application would start more containers.
     */
    private class ExcludeTestConfigurations : TypeExcludeFilter() {
        override fun match(metadataReader: MetadataReader, metadataReaderFactory: MetadataReaderFactory) =
            metadataReader.annotationMetadata.hasAnnotation(TestConfiguration::class.java.name)

        override fun equals(other: Any?) = other is ExcludeTestConfigurations

        override fun hashCode() = javaClass.hashCode()
    }

    private companion object {
        const val CRYPTO = "/api/v1/admin/crypto"
        const val REENCRYPTED = "MASTER_KEYS_REENCRYPTED"
    }
}
