package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.FolioApplication
import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.audit.AuditRecords
import io.github.big_sw_little_sw.folio.credential.internal.CryptoProperties
import io.github.big_sw_little_sw.folio.credential.internal.EncryptedKeyRepository
import io.github.big_sw_little_sw.folio.credential.internal.PrivateKeyCipher
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.Signature
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Master-key rotation and the startup check against a real database. The test ring has versions 1 and 2. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CryptoIntegrationTest(
    @Autowired private val crypto: CryptoService,
    @Autowired private val credentials: CredentialService,
    @Autowired private val keyPairs: CredentialKeyPairs,
    @Autowired private val repository: EncryptedKeyRepository,
    @Autowired private val cipher: PrivateKeyCipher,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val postgres: PostgreSQLContainer,
    @Value("\${folio.crypto.master-keys.1}") private val secret1: String,
    @Value("\${folio.crypto.master-keys.2}") private val secret2: String,
) {
    /** The ring as it was before version 2 became active. */
    private val previousCipher by lazy {
        PrivateKeyCipher(CryptoProperties(mapOf(1 to secret1, 2 to secret2), activeKeyVersion = 1))
    }

    @BeforeEach
    fun deleteAll() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from credential_key").update()
        jdbc.sql("delete from credential").update()
        authenticateAs(SUPER_ADMIN)
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `re-encryption moves keys under older versions to the active one and keeps them usable`() {
        val first = credentials.create("example", uniqueCredentialName())
        credentials.regenerate(first.id)
        val second = credentials.create("example", uniqueCredentialName())
        val publicKeys = listOf(first, second).associate { it.id to keyPairs.active(it.id).keyPair.public }
        encryptAllUnderVersion1()
        assertEquals(MasterKeyUsage(2, mapOf(1 to 3, 2 to 0)), crypto.usage())

        val usage = crypto.reencrypt()

        assertEquals(MasterKeyUsage(2, mapOf(1 to 0, 2 to 3)), usage)
        publicKeys.forEach { (id, publicKey) -> assertTrue(signsWith(id, publicKey)) }
    }

    @Test
    fun `a re-encryption pass writes one audit record with counts per master-key version`() {
        repeat(2) { credentials.create("example", uniqueCredentialName()) }
        encryptAllUnderVersion1()

        crypto.reencrypt()
        crypto.reencrypt()

        val (rerun, pass) = AuditRecords(jdbc).ofMasterKeyRing(2)
        assertEquals("MASTER_KEYS_REENCRYPTED", pass.action)
        assertEquals(SUPER_ADMIN, pass.actorSubject)
        assertEquals(null, pass.resourceId)
        assertEquals(
            mapOf(
                "complete" to true,
                "activeVersion" to 2,
                "reencryptedByVersion" to mapOf("1" to 2),
                "keysByVersion" to mapOf("1" to 0, "2" to 2),
            ),
            pass.details,
        )
        assertEquals(emptyMap<String, Int>(), rerun.details["reencryptedByVersion"])
    }

    @Test
    fun `a pass that fails partway still records the keys done so far, as incomplete`() {
        repeat(2) { credentials.create("example", uniqueCredentialName()) }
        encryptAllUnderVersion1()
        // Keys are re-encrypted in ID order; the last one cannot be decrypted.
        jdbc
            .sql(
                "update credential_key set ciphertext = set_byte(ciphertext, 0, (get_byte(ciphertext, 0) + 1) % 256) " +
                    "where id = (select id from credential_key order by id desc limit 1)",
            ).update()

        assertFailsWith<AEADBadTagException> { crypto.reencrypt() }

        val failed = AuditRecords(jdbc).ofMasterKeyRing(1).single()
        assertEquals(false, failed.details["complete"])
        assertEquals(mapOf("1" to 1), failed.details["reencryptedByVersion"])
        assertEquals(mapOf("1" to 1, "2" to 1), failed.details["keysByVersion"])
    }

    @Test
    fun `re-running re-encryption changes nothing`() {
        credentials.create("example", uniqueCredentialName())
        encryptAllUnderVersion1()
        crypto.reencrypt()
        val before = ciphertexts()

        assertEquals(MasterKeyUsage(2, mapOf(1 to 0, 2 to 1)), crypto.reencrypt())

        assertEquals(before.keys, ciphertexts().keys)
        before.forEach { (id, ciphertext) -> assertContentEquals(ciphertext, ciphertexts().getValue(id)) }
    }

    @Test
    fun `concurrent re-encryptions are safe`() {
        val ids = (1..5).map { credentials.create("example", uniqueCredentialName()).id }
        encryptAllUnderVersion1()
        val threads = 4
        val barrier = CyclicBarrier(threads)
        val executor = Executors.newFixedThreadPool(threads)

        val usages =
            try {
                (1..threads)
                    .map {
                        executor.submit<MasterKeyUsage> {
                            authenticateAs(SUPER_ADMIN)
                            barrier.await()
                            crypto.reencrypt()
                        }
                    }.map { it.get(1, TimeUnit.MINUTES) }
            } finally {
                executor.shutdown()
            }

        usages.forEach { assertEquals(MasterKeyUsage(2, mapOf(1 to 0, 2 to 5)), it) }
        ids.forEach { assertTrue(signsWith(it, keyPairs.active(it).keyPair.public)) }
    }

    @Test
    fun `re-encryption cannot bring back a key retired after it was read`() {
        val created = credentials.create("example", uniqueCredentialName())
        encryptAllUnderVersion1()
        val stored = repository.findNotUnder(2).single()
        val plaintext = previousCipher.decrypt(stored.encrypted, stored.credentialId, stored.id)

        credentials.replace(created.id)

        val reencrypted = cipher.encrypt(plaintext, stored.credentialId, stored.id)
        plaintext.fill(0)
        assertFalse(repository.replaceEncryption(stored.id, stored.encrypted.masterKeyVersion, reencrypted))
        val wiped =
            jdbc
                .sql(
                    """
                    select num_nulls(algorithm, master_key_version, salt, nonce, ciphertext)
                    from credential_key where id = :id
                    """.trimIndent(),
                ).param("id", stored.id.value)
                .query(Int::class.java)
                .single()
        assertEquals(5, wiped)
    }

    @Test
    fun `startup fails naming a master-key version that is not configured`() {
        credentials.create("example", uniqueCredentialName())
        jdbc.sql("update credential_key set master_key_version = 99").update()
        try {
            val failure = assertFailsWith<Exception> { startApplication().close() }

            val messages = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(
                messages.any { "master-key versions [99]" in it && "folio.crypto.master-keys" in it },
                messages.toString(),
            )
        } finally {
            jdbc.sql("delete from credential_key").update()
        }
    }

    /** Starts a second application against this test's database, as a deployment would at startup. */
    private fun startApplication(): ConfigurableApplicationContext =
        SpringApplicationBuilder(FolioApplication::class.java)
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> {
                    it.beanFactory.registerSingleton("excludeTestcontainers", ExcludeTestcontainers())
                },
            ).properties(
                "spring.datasource.url=${postgres.jdbcUrl}",
                "spring.datasource.username=${postgres.username}",
                "spring.datasource.password=${postgres.password}",
                "server.port=0",
                "management.server.port=0",
            ).run()

    /** Rewrites every stored key as the application did while version 1 was active. */
    private fun encryptAllUnderVersion1() {
        repository.findNotUnder(1).forEach { key ->
            val plaintext = cipher.decrypt(key.encrypted, key.credentialId, key.id)
            val encrypted = previousCipher.encrypt(plaintext, key.credentialId, key.id)
            plaintext.fill(0)
            check(repository.replaceEncryption(key.id, key.encrypted.masterKeyVersion, encrypted))
        }
    }

    private fun ciphertexts(): Map<KeyId, ByteArray> =
        repository.findNotUnder(0).associate { it.id to it.encrypted.ciphertext }

    private fun signsWith(
        id: CredentialId,
        publicKey: java.security.PublicKey,
    ): Boolean {
        val message = "challenge".toByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPairs.active(id).keyPair.private)
        signer.update(message)
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(message)
        return verifier.verify(signer.sign())
    }

    /** Keeps the second application on this test's database instead of starting another container. */
    private class ExcludeTestcontainers : TypeExcludeFilter() {
        override fun match(
            metadataReader: MetadataReader,
            metadataReaderFactory: MetadataReaderFactory,
        ) = metadataReader.classMetadata.className == TestcontainersConfiguration::class.java.name

        override fun equals(other: Any?) = other is ExcludeTestcontainers

        override fun hashCode() = javaClass.hashCode()
    }
}
