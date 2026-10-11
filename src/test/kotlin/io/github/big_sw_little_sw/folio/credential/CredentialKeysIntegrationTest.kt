package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.credential.internal.OpenSshPublicKey
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCredentials
import io.github.big_sw_little_sw.testing.signsFor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.PublicKey
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests check the public interface of the credential module for other modules (design 8.3): `Credentials` and
 * `CredentialKeys`. The keys come from the database, so the tests also show that the stored encryption works.
 */
@FolioIntegrationTest
class CredentialKeysIntegrationTest(
    @Autowired private val credentials: Credentials,
    @Autowired private val keys: CredentialKeys,
    @Autowired private val fixture: TestCredentials,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val jdbc: JdbcClient,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)
    private val bob = Caller.Authenticated("bob", setOf("team-b"), null)

    @Test
    fun `withActiveKey gives the active key pair, which signs for the public key of the credential`() {
        val credential = fixture.credential()

        val (gitInstance, keyId) = keys.withActiveKey(credential.id) { it.gitInstance to it.keyId }
        keys.withActiveKey(credential.id) {
            val publicKey = publicKeyOf(credential, it.keyId)
            assertEquals(publicKey, it.keyPair.public)
            assertTrue(signsFor(it.keyPair.private, publicKey))
        }

        assertEquals(Slug("github"), gitInstance)
        assertEquals(credential.keys.single().id, keyId)
    }

    @Test
    fun `withPendingKey gives only the pending key that the caller names`() {
        val credential = fixture.credential()
        val pending = fixture.pendingKey(credential.id)

        keys.withPendingKey(credential.id, pending.id) {
            assertEquals(pending.id, it.keyId)
            assertEquals(OpenSshPublicKey.decode(pending.publicKey), it.keyPair.public)
            assertTrue(signsFor(it.keyPair.private, it.keyPair.public))
        }
        val active = credential.keys.single().id
        assertFailsWith<CredentialException.KeyNotPending> { keys.withPendingKey(credential.id, active) {} }
        fixture.activate(credential.id, pending.id)
        assertFailsWith<CredentialException.KeyNotPending> { keys.withPendingKey(credential.id, pending.id) {} }
        keys.withActiveKey(credential.id) { assertEquals(pending.id, it.keyId) }
    }

    @Test
    fun `a disabled or missing credential gives no key`() {
        val credential = fixture.credential()
        fixture.disable(credential.id)
        val missing = CredentialId(UUID.randomUUID())

        assertFailsWith<CredentialException.CredentialDisabled> { keys.withActiveKey(credential.id) {} }
        assertFailsWith<CredentialException.CredentialDisabled> {
            keys.withPendingKey(credential.id, credential.keys.single().id) {}
        }
        assertFailsWith<CredentialException.CredentialNotFound> { keys.withActiveKey(missing) {} }
        assertFailsWith<CredentialException.CredentialNotFound> {
            keys.withPendingKey(
                missing,
                KeyId(UUID.randomUUID()),
            ) {}
        }
    }

    /**
     * The test deletes its credentials, because the re-encryption tests must not find keys that do not decrypt. The
     * failure names the key and the credential, and no crypto exception type reaches the caller.
     */
    @Test
    fun `a ciphertext that moves to another row does not decrypt`() {
        val first = fixture.credential()
        val second = fixture.credential()
        try {
            jdbc
                .sql(
                    """
                    update credential_key set (algorithm, master_key_version, salt, nonce, ciphertext) =
                        (select algorithm, master_key_version, salt, nonce, ciphertext from credential_key
                         where credential_id = :from and status = 'ACTIVE')
                    where credential_id = :to and status = 'ACTIVE'
                    """.trimIndent(),
                ).param("from", first.id.value)
                .param("to", second.id.value)
                .update()

            val failure = assertFailsWith<IllegalStateException> { keys.withActiveKey(second.id) {} }
            assertEquals(
                "Key ${second.keys.single().id.toApiId()} of credential ${second.id.toApiId()} under master-key " +
                    "version 2 does not decrypt.",
                failure.message,
            )
            keys.withActiveKey(first.id) {}
        } finally {
            listOf(first, second).forEach { fixture.delete(it.id) }
        }
    }

    /** A long Git fetch in the block must hold no database connection (design 8.3). */
    @Test
    fun `the block runs outside a transaction`() {
        val credential = fixture.credential()
        val pending = fixture.pendingKey(credential.id)

        keys.withActiveKey(credential.id) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()) }
        keys.withPendingKey(credential.id, pending.id) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        }
    }

    @Test
    fun `requireUsable needs MANAGE on the Namespace that owns the credential and an enabled credential`() {
        val namespace = catalog.namespace()
        val credential = fixture.credential(namespace)

        assertFailsWith<CatalogException.Forbidden> { credentials.requireUsable(alice, credential.id) }
        assertFailsWith<CatalogException.Unauthenticated> { credentials.requireUsable(Caller.Anonymous, credential.id) }
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        assertEquals(
            UsableCredential(credential.id, namespace, Slug("github")),
            credentials.requireUsable(alice, credential.id),
        )
        fixture.disable(credential.id)
        assertFailsWith<CredentialException.CredentialDisabled> { credentials.requireUsable(alice, credential.id) }
        // Authorization comes before the state, so a caller without MANAGE does not learn that the credential is
        // disabled.
        assertFailsWith<CatalogException.Forbidden> { credentials.requireUsable(bob, credential.id) }
        assertFailsWith<CatalogException.Unauthenticated> { credentials.requireUsable(Caller.Anonymous, credential.id) }
        assertFailsWith<CredentialException.CredentialNotFound> {
            credentials.requireUsable(alice, CredentialId(UUID.randomUUID()))
        }
    }

    @Test
    fun `DecryptedKey toString shows no key material`() {
        val credential = fixture.credential()

        keys.withActiveKey(credential.id) {
            assertEquals("DecryptedKey(gitInstance=github, keyId=${it.keyId.toApiId()})", it.toString())
        }
    }

    private fun publicKeyOf(credential: Credential, key: KeyId): PublicKey =
        OpenSshPublicKey.decode(credential.keys.single { it.id == key }.publicKey)
}
