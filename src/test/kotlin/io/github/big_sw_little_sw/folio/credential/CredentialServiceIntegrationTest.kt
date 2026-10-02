package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.credential.internal.OpenSshPublicKey
import io.github.big_sw_little_sw.folio.policy.PermissionDeniedException
import io.github.big_sw_little_sw.folio.security.BOOTSTRAP_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import java.security.KeyPair
import java.security.Signature
import java.util.HexFormat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Key lifecycle, storage and decryption through the services, checked against the stored rows. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CredentialServiceIntegrationTest(
    @Autowired private val service: CredentialService,
    @Autowired private val keyPairs: CredentialKeyPairs,
    @Autowired private val jdbc: JdbcClient,
) {
    @BeforeEach
    fun deleteAll() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from credential_key").update()
        jdbc.sql("delete from credential").update()
        authenticateAs(BOOTSTRAP_ADMIN)
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the active key pair decrypts, matches the public key and signs`() {
        val credential = service.create("example")

        val keyPair = keyPairs.active(credential.id).keyPair

        assertEquals(OpenSshPublicKey.decode(credential.keys.single().publicKey), keyPair.public)
        assertTrue(signsFor(keyPair))
    }

    @Test
    fun `the private key is stored only encrypted`() {
        val credential = service.create("example")
        val pkcs8 =
            keyPairs
                .active(credential.id)
                .keyPair.private.encoded
        val seed = HexFormat.of().formatHex(pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size))

        // bytea columns appear in hex, so the seed would show as its hex digits.
        val row = jdbc.sql("select row_to_json(k)::text from credential_key k").query(String::class.java).single()

        assertTrue(row.contains("\"ciphertext\""))
        assertFalse(row.contains(seed))
    }

    @Test
    fun `activation retires the previous key, wipes its encrypted private key and keeps its fingerprint`() {
        val created = service.create("example")
        val oldKey = created.keys.single()
        val pending = service.regenerate(created.id).keys.single { it.status == KeyStatus.PENDING }
        assertEquals(oldKey.publicKey, OpenSshPublicKey.of(keyPairs.active(created.id).keyPair.public).text)

        val activated = service.activate(created.id, pending.id)

        assertEquals(
            listOf(oldKey.copy(status = KeyStatus.RETIRED), pending.copy(status = KeyStatus.ACTIVE)),
            activated.keys,
        )
        assertEquals(5, wipedColumns(oldKey.id))
        assertEquals(pending.publicKey, OpenSshPublicKey.of(keyPairs.active(created.id).keyPair.public).text)
    }

    @Test
    fun `emergency replacement switches the active key pair at once`() {
        val created = service.create("example")
        val before = keyPairs.active(created.id).keyPair

        val replaced = service.replace(created.id)

        val after = keyPairs.active(created.id).keyPair
        assertNotEquals(before.public, after.public)
        assertEquals(
            OpenSshPublicKey.decode(replaced.keys.single { it.status == KeyStatus.ACTIVE }.publicKey),
            after.public,
        )
        assertEquals(5, wipedColumns(created.keys.single().id))
    }

    @Test
    fun `a disabled credential gives no key pair`() {
        val created = service.create("example")

        service.disable(created.id)

        assertFailsWith<CredentialDisabledException> { keyPairs.active(created.id).keyPair }
        assertFailsWith<CredentialDisabledException> { service.regenerate(created.id) }
    }

    @Test
    fun `a ciphertext moved to another key's row does not decrypt`() {
        val first = service.create("example")
        val second = service.create("example")
        jdbc
            .sql(
                """
                update credential_key target
                set algorithm = source.algorithm, master_key_version = source.master_key_version,
                    salt = source.salt, nonce = source.nonce, ciphertext = source.ciphertext
                from credential_key source
                where source.credential_id = :from and target.credential_id = :to
                """.trimIndent(),
            ).param("from", first.id.value)
            .param("to", second.id.value)
            .update()

        assertTrue(signsFor(keyPairs.active(first.id).keyPair))
        assertFailsWith<AEADBadTagException> { keyPairs.active(second.id).keyPair }
    }

    @Test
    fun `concurrent regenerations create one pending key`() {
        val created = service.create("example")
        val threads = 4
        val barrier = CyclicBarrier(threads)
        val executor = Executors.newFixedThreadPool(threads)

        val outcomes =
            try {
                (1..threads)
                    .map {
                        executor.submit<Result<Credential>> {
                            authenticateAs(BOOTSTRAP_ADMIN)
                            barrier.await()
                            runCatching { service.regenerate(created.id) }
                        }
                    }.map { it.get(1, TimeUnit.MINUTES) }
            } finally {
                executor.shutdown()
            }

        assertEquals(1, outcomes.count { it.isSuccess })
        assertTrue(outcomes.filter { it.isFailure }.all { it.exceptionOrNull() is PendingKeyExistsException })
        assertEquals(1, service.get(created.id).keys.count { it.status == KeyStatus.PENDING })
    }

    @Test
    fun `the active key pair stays consistent while the key is replaced concurrently`() {
        val created = service.create("example")
        val executor = Executors.newSingleThreadExecutor()

        try {
            val replacements =
                executor.submit {
                    authenticateAs(BOOTSTRAP_ADMIN)
                    repeat(ROUNDS) { service.replace(created.id) }
                }
            repeat(ROUNDS) { assertTrue(signsFor(keyPairs.active(created.id).keyPair)) }
            replacements.get(1, TimeUnit.MINUTES)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `a non-admin may not view or change credentials`() {
        val created = service.create("example")
        authenticateAs("alice")

        assertFailsWith<PermissionDeniedException> { service.get(created.id) }
        assertFailsWith<PermissionDeniedException> { service.list() }
        assertFailsWith<PermissionDeniedException> { service.create("example") }
    }

    private companion object {
        const val ROUNDS = 30
    }

    /** How many of the five encryption columns of [keyId] are null. */
    private fun wipedColumns(keyId: KeyId): Int =
        jdbc
            .sql(
                """
                select num_nulls(algorithm, master_key_version, salt, nonce, ciphertext)
                from credential_key where id = :id
                """.trimIndent(),
            ).param("id", keyId.value)
            .query(Int::class.java)
            .single()

    private fun signsFor(keyPair: KeyPair): Boolean {
        val message = "challenge".toByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(message)
        val signature = signer.sign()
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(keyPair.public)
        verifier.update(message)
        return verifier.verify(signature)
    }
}
