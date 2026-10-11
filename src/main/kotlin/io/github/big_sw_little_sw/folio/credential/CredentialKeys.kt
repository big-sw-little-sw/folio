package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.internal.CredentialKey
import io.github.big_sw_little_sw.folio.credential.internal.CredentialStatus
import io.github.big_sw_little_sw.folio.credential.internal.KeyRepository
import io.github.big_sw_little_sw.folio.credential.internal.KeyStatus
import io.github.big_sw_little_sw.folio.credential.internal.SshKeys
import org.springframework.stereotype.Service
import java.security.KeyPair

/**
 * A decrypted key and the Git instance of its credential. The class is not a data class and overrides `toString`, so
 * a log line never shows the key.
 */
class DecryptedKey(val gitInstance: Slug, val keyId: KeyId, val keyPair: KeyPair) {
    override fun toString() = "DecryptedKey(gitInstance=$gitInstance, keyId=${keyId.toApiId()})"
}

/**
 * Decrypted keys for Git connections (design 8.3). This class does not authorize, because sync runs without a person.
 * Thus only the `git` module calls this class. No HTTP route calls it, and a modularity test verifies this. Private
 * keys never leave Folio.
 *
 * Each method decrypts the key for the duration of the block only. The caller must not keep, log or store the key.
 * The block runs outside a transaction, so a long Git fetch holds no database connection. Each method throws
 * `CredentialNotFound` (404) for a credential that does not exist and `CredentialDisabled` (409) for a disabled
 * credential. A key that does not decrypt is an unexpected error: an `IllegalStateException` whose message names the
 * key and the credential, and never a secret.
 */
@Service
class CredentialKeys(private val keys: KeyRepository, private val sshKeys: SshKeys) {
    /** Runs [block] with the active key. */
    fun <T> withActiveKey(credential: CredentialId, block: (DecryptedKey) -> T): T {
        val found = usable(credential, keys.find(credential, KeyStatus.ACTIVE))
        // Each credential always has one active key (design 11.1), so a missing key is a broken invariant.
        val key = checkNotNull(found.key) { "Credential ${credential.toApiId()} has no active key." }
        return block(DecryptedKey(found.gitInstance, key.id, sshKeys.open(key)))
    }

    /**
     * Runs [block] with the pending key [key]. The Git check uses this method, so an administrator can test a new key
     * before the activation (design 10.3). Throws `KeyNotPending` (409) if [key] is not the pending key.
     */
    fun <T> withPendingKey(credential: CredentialId, key: KeyId, block: (DecryptedKey) -> T): T {
        val found = usable(credential, keys.find(credential, KeyStatus.PENDING))
        val pending = found.key?.takeIf { it.id == key } ?: throw CredentialException.KeyNotPending(credential, key)
        return block(DecryptedKey(found.gitInstance, pending.id, sshKeys.open(pending)))
    }

    private fun usable(id: CredentialId, found: CredentialKey?): CredentialKey {
        if (found == null) throw CredentialException.CredentialNotFound(id)
        if (found.credentialStatus == CredentialStatus.DISABLED) throw CredentialException.CredentialDisabled(id)
        return found
    }
}
