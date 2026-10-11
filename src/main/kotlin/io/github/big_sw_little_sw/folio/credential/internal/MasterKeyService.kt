package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * The master-key status: the active version and the number of encrypted keys for each version. The map has each
 * configured version and each stored version, in version order. An operator can remove a version from the
 * configuration when the version has zero keys.
 */
data class MasterKeyStatus(val activeVersion: Int, val keysByVersion: Map<Int, Int>)

/**
 * The result of one re-encryption run. [reencrypted] maps each previous version to the number of keys that the run
 * encrypted again with the active version.
 */
data class Reencryption(val reencrypted: Map<Int, Int>, val status: MasterKeyStatus)

/**
 * Master-key status and re-encryption (design 11.3). The master-key ring is not in the tree, so only super admins can
 * use these operations (design 5.1).
 */
@Service
class MasterKeyService(
    private val keys: KeyRepository,
    private val cipher: PrivateKeyCipher,
    private val ring: MasterKeyRing,
    private val access: Access,
    private val audit: CredentialAudit,
    private val transactions: TransactionTemplate,
) {
    fun status(caller: Caller): MasterKeyStatus {
        access.requireSuperAdmin(caller)
        return currentStatus()
    }

    /**
     * Encrypts again each key that does not use the active version. After one run, the active version encrypts all
     * keys.
     *
     * The run has no transaction of its own. Each row update is one transaction, so a failure keeps the rows that the
     * run already changed. A row changes only if the version that the run read still encrypts it (design 11.3). A key
     * that does not decrypt stops the run with an `IllegalStateException` that names the key and its credential.
     *
     * A run that changed keys writes one audit record at the end, also after a failure. Then the record has the keys
     * that the run changed, and `complete` is false. A run that changed nothing writes no record (design 14).
     */
    fun reencrypt(caller: Caller): Reencryption {
        access.requireSuperAdmin(caller)
        val active = ring.activeVersion
        val reencrypted = sortedMapOf<Int, Int>()
        val failure =
            runCatching {
                keys.notUnder(active).forEach { key ->
                    if (reencrypt(key)) reencrypted.merge(key.encrypted.masterKeyVersion, 1, Int::plus)
                }
            }.exceptionOrNull()
        if (failure != null) {
            // A failed audit write must not hide the failure of the run. Thus the audit failure is only a suppressed
            // exception of the run failure.
            runCatching { record(caller, active, reencrypted, complete = false) }.onFailure(failure::addSuppressed)
            throw failure
        }
        record(caller, active, reencrypted, complete = true)
        return Reencryption(reencrypted, currentStatus())
    }

    /** Returns false if a parallel run or a retirement changed the row first. */
    private fun reencrypt(key: StoredKey): Boolean = keys.replaceEncryption(
        key.id,
        key.encrypted.masterKeyVersion,
        cipher.reencrypt(key.encrypted, key.credentialId, key.id),
    )

    private fun record(caller: Caller, active: Int, reencrypted: Map<Int, Int>, complete: Boolean) {
        if (reencrypted.isEmpty()) return
        transactions.executeWithoutResult { audit.reencrypted(caller, active, reencrypted, complete) }
    }

    private fun currentStatus(): MasterKeyStatus {
        val counts = keys.countByVersion()
        val versions = (ring.versions + counts.keys).sorted()
        return MasterKeyStatus(ring.activeVersion, versions.associateWith { counts[it] ?: 0 })
    }
}
