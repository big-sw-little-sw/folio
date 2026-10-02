package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.credential.internal.CryptoProperties
import io.github.big_sw_little_sw.folio.credential.internal.EncryptedKeyRepository
import io.github.big_sw_little_sw.folio.credential.internal.PrivateKeyCipher
import io.github.big_sw_little_sw.folio.credential.internal.StoredKey
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * How many stored private keys each master-key version encrypts, for every configured version. A version with
 * no keys can be removed from `folio.crypto.master-keys`.
 */
data class MasterKeyUsage(
    val activeVersion: Int,
    /** Ordered by version. */
    val keysByVersion: Map<Int, Int>,
)

/** Master-key rotation (ADR 0020). Authorized at the root like credentials, so super admins only (ADR 0018). */
@Service
class CryptoService(
    private val keys: EncryptedKeyRepository,
    private val cipher: PrivateKeyCipher,
    private val masterKeys: CryptoProperties,
    private val policy: PolicyService,
    private val events: ApplicationEventPublisher,
    private val transactions: TransactionTemplate,
) {
    @Transactional(readOnly = true)
    fun usage(): MasterKeyUsage {
        policy.requireAllowed(Action.CRYPTO_MANAGE, ROOT)
        return currentUsage()
    }

    /**
     * Re-encrypts every key not under the active master-key version with a new salt and nonce.
     *
     * Deliberately not transactional: each row's update commits on its own, so a failure keeps the rows already
     * done. The update applies only while the row is still under the version it was read with. A concurrent
     * pass that moved it first, or a retirement that wiped it, makes the update match nothing, so concurrent
     * passes and re-runs are safe.
     *
     * The pass publishes one [MasterKeysReencrypted] at the end, in a transaction of its own (ADR 0038).
     */
    fun reencrypt(): MasterKeyUsage {
        policy.requireAllowed(Action.CRYPTO_MANAGE, ROOT)
        val reencrypted =
            keys
                .findNotUnder(masterKeys.activeKeyVersion)
                .filter(::reencrypt)
                .groupingBy { it.encrypted.masterKeyVersion }
                .eachCount()
        val usage = currentUsage()
        transactions.executeWithoutResult { events.publishEvent(MasterKeysReencrypted(reencrypted, usage)) }
        return usage
    }

    /** Returns false if another pass or a retirement changed the row first. */
    private fun reencrypt(key: StoredKey): Boolean {
        val plaintext = cipher.decrypt(key.encrypted, key.credentialId, key.id)
        try {
            return keys.replaceEncryption(
                key.id,
                key.encrypted.masterKeyVersion,
                cipher.encrypt(plaintext, key.credentialId, key.id),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun currentUsage(): MasterKeyUsage {
        val counts = keys.countByMasterKeyVersion()
        return MasterKeyUsage(
            masterKeys.activeKeyVersion,
            masterKeys.versions.sorted().associateWith { counts[it] ?: 0 },
        )
    }

    private companion object {
        /** The root above all namespaces; no rules attach there, so only super admins pass (ADR 0012). */
        val ROOT = emptyList<ResourceRef>()
    }
}
