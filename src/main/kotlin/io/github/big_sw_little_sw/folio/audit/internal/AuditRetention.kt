package io.github.big_sw_little_sw.folio.audit.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/** `folio.audit`: how long Folio keeps audit records. */
@ConfigurationProperties("folio.audit")
data class AuditProperties(val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS)) {
    init {
        require(retention.isPositive) { "folio.audit.retention must be positive" }
    }

    private companion object {
        const val DEFAULT_RETENTION_DAYS = 90L
    }
}

/**
 * Deletes audit records older than the retention, one time each day on each instance. Each batch is a statement of
 * its own, without a transaction, so a large first run never holds many locks for a long time. With `SKIP LOCKED`,
 * the instances share the work and never wait for each other.
 */
@Component
class AuditRetention(private val jdbc: JdbcClient, private val properties: AuditProperties) {
    /** Without the short first delay, an instance that restarts more often than once a day never deletes records. */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "P1D")
    fun deleteExpired() {
        var total = 0
        do {
            val deleted = deleteBatch()
            total += deleted
        } while (deleted == BATCH_SIZE)
        log.info("Deleted {} audit records older than {}", total, properties.retention)
    }

    private fun deleteBatch(): Int = jdbc
        .sql(
            """
                delete from audit_event where id in (
                    select id from audit_event
                    where occurred_at < now() - :retentionSeconds * interval '1 second'
                    limit :limit
                    for update skip locked
                )
            """.trimIndent(),
        ).param("retentionSeconds", properties.retention.toSeconds())
        .param("limit", BATCH_SIZE)
        .update()

    companion object {
        const val BATCH_SIZE = 1000
        private val log = LoggerFactory.getLogger(AuditRetention::class.java)
    }
}
