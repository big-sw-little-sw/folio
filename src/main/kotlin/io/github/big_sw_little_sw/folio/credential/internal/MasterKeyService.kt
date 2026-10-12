package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.audit.AuditEntry
import io.github.big_sw_little_sw.folio.audit.AuditLog
import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * The number of encrypted keys for each configured or stored version, in version order. An operator can remove a
 * version from the configuration when the version has zero keys.
 */
data class MasterKeyStatus(val activeVersion: Int, val keysByVersion: Map<Int, Int>)

/** [reencrypted] maps each previous version to the number of keys that the run moved to the active version. */
data class Reencryption(val reencrypted: Map<Int, Int>, val status: MasterKeyStatus)

/**
 * Master-key status and re-encryption. The master-key ring is not in the tree and has no rules, so only super admins
 * can use these operations.
 *
 * The startup stops if a stored key uses a master-key version that the ring does not have. Without this check, Folio
 * would fail later, at the first sync with that key. The check runs after all singletons exist, so Flyway has already
 * migrated the schema. The web server starts after the check, so no request comes before it.
 */
@Service
class MasterKeyService(
    private val keyRepository: KeyRepository,
    private val cipher: PrivateKeyCipher,
    private val ring: MasterKeyRing,
    private val access: Access,
    private val auditLog: AuditLog,
    private val transactions: TransactionTemplate,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val missing = keyRepository.countByVersion().keys - ring.versions
        check(missing.isEmpty()) {
            "Stored keys use the master-key versions ${missing.sorted()}, but folio.crypto.master-keys does not have " +
                "them. Add these versions again, run the re-encryption, and then remove them."
        }
    }

    fun status(caller: Caller): MasterKeyStatus {
        access.requireSuperAdmin(caller)
        return currentStatus()
    }

    /**
     * Encrypts again each key that does not use the active version. After one run, the active version encrypts all
     * keys.
     *
     * The run has no transaction of its own. Each row update is one transaction, so a failure keeps the rows that the
     * run already changed. A key that does not decrypt stops the run.
     *
     * A run that moved keys writes one audit record at the end, also after a failure. After a failure, the record has
     * `complete` set to false in its details. A run that moved no key changed nothing, so it writes no record.
     */
    fun reencrypt(caller: Caller): Reencryption {
        access.requireSuperAdmin(caller)
        val active = ring.activeVersion
        val reencrypted = sortedMapOf<Int, Int>()
        val failure =
            runCatching {
                keyRepository.notUnder(active).forEach { key ->
                    if (reencrypt(key)) reencrypted.merge(key.encrypted.masterKeyVersion, 1, Int::plus)
                }
            }.exceptionOrNull()
        if (failure != null) {
            // A failed audit write must not hide the failure of the run, so the audit failure becomes a suppressed
            // exception of the run failure.
            runCatching { record(caller, active, reencrypted, complete = false) }.onFailure(failure::addSuppressed)
            throw failure
        }
        record(caller, active, reencrypted, complete = true)
        return Reencryption(reencrypted, currentStatus())
    }

    private fun reencrypt(key: StoredKey): Boolean = keyRepository.replaceEncryption(
        key.id,
        key.encrypted.masterKeyVersion,
        cipher.reencrypt(key.encrypted, key.credentialId, key.id),
    )

    /** The ring has no ID and no path, so the record has neither. */
    private fun record(caller: Caller, active: Int, reencrypted: Map<Int, Int>, complete: Boolean) {
        if (reencrypted.isEmpty()) return
        val entry =
            AuditEntry(
                caller = caller,
                operation = "MASTER_KEYS_REENCRYPTED",
                resourceType = "MASTER_KEY_RING",
                resourceId = null,
                path = null,
                details =
                mapOf(
                    "activeVersion" to active,
                    "reencrypted" to reencrypted.toSortedMap(),
                    "complete" to complete,
                ),
            )
        transactions.executeWithoutResult { auditLog.record(entry) }
    }

    private fun currentStatus(): MasterKeyStatus {
        val counts = keyRepository.countByVersion()
        val versions = (ring.versions + counts.keys).sorted()
        return MasterKeyStatus(ring.activeVersion, versions.associateWith { counts[it] ?: 0 })
    }
}
