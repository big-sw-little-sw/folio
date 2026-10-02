package io.github.big_sw_little_sw.folio.audit.internal

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration

/**
 * Prunes audit records once a day (ADR 0041). The first run comes soon after start rather than a day later, so that
 * instances restarted more often than daily still prune.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class AuditScheduling(
    private val pruning: AuditPruning,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addFixedDelayTask(FixedDelayTask(pruning::prune, INTERVAL, FIRST_DELAY))
    }

    private companion object {
        val INTERVAL: Duration = Duration.ofDays(1)
        val FIRST_DELAY: Duration = Duration.ofMinutes(10)
    }
}
