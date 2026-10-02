package io.github.big_sw_little_sw.folio.audit.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `folio.audit.retention`: how long audit records are kept before the daily pruning deletes them (ADR 0041). */
@ConfigurationProperties("folio.audit")
data class AuditProperties(
    val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
) {
    init {
        require(retention.isPositive) { "folio.audit.retention must be positive" }
    }

    private companion object {
        const val DEFAULT_RETENTION_DAYS = 90L
    }
}
