package io.github.big_sw_little_sw.folio.audit.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deletes audit records older than `folio.audit.retention` (ADR 0041). Every instance prunes; concurrent runs skip
 * each other's rows. Deliberately not transactional: each batch commits on its own, so a large first run never holds
 * one long transaction or a lock on every old row.
 */
@Component
class AuditPruning(
    private val records: AuditRepository,
    private val properties: AuditProperties,
) {
    /** Returns how many records this run deleted. */
    fun prune(): Int {
        var total = 0
        do {
            val deleted = records.deleteOlderThan(properties.retention, BATCH_SIZE)
            total += deleted
        } while (deleted == BATCH_SIZE)
        log.info("Pruned {} audit records", total)
        return total
    }

    companion object {
        const val BATCH_SIZE = 1000
        private val log = LoggerFactory.getLogger(AuditPruning::class.java)
    }
}
