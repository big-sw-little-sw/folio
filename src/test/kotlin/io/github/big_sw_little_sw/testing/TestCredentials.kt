package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.credential.internal.CredentialService
import io.github.big_sw_little_sw.folio.credential.internal.Key
import io.github.big_sw_little_sw.folio.credential.internal.KeyStatus
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.uniqueSlug
import org.springframework.boot.test.context.TestComponent
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * This component creates credentials for tests, as a super admin. It is the one place in the tests that changes state
 * through the internal `CredentialService`. The tests check the operations themselves through the HTTP routes or the
 * public interface of the module.
 */
@TestComponent
class TestCredentials(
    private val credentials: CredentialService,
    private val catalog: TestCatalog,
    private val jdbc: JdbcClient,
) {
    /** Creates a credential with its first key, which is active. */
    fun credential(
        namespace: NamespaceId = catalog.namespace(),
        name: String = uniqueSlug("cred"),
        gitInstance: String = "github",
    ): Credential = credentials.create(SUPER_ADMIN, namespace, Slug(name), Slug(gitInstance))

    /** Adds a pending key to the credential and returns that key. */
    fun pendingKey(id: CredentialId): Key = checkNotNull(credentials.regenerate(SUPER_ADMIN, id).pendingKey)

    fun activate(id: CredentialId, key: KeyId) {
        credentials.activate(SUPER_ADMIN, id, key)
    }

    fun disable(id: CredentialId) {
        credentials.disable(SUPER_ADMIN, id)
    }

    fun delete(id: CredentialId) {
        credentials.delete(SUPER_ADMIN, id)
    }

    /**
     * Replaces the ciphertext of the active key with one byte, so the key no longer decrypts. The credential module
     * then throws `IllegalStateException`, and its callers treat that exception as an unexpected error.
     */
    fun corruptActiveKey(id: CredentialId) {
        jdbc
            .sql("update credential_key set ciphertext = '\\x00' where credential_id = :id and status = 'ACTIVE'")
            .param("id", id.value)
            .update()
    }
}

/** The active key of the credential. Each credential always has exactly one active key. */
val Credential.activeKey: Key get() = keys.single { it.status == KeyStatus.ACTIVE }
