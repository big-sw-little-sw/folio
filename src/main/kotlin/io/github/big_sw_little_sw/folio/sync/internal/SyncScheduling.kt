package io.github.big_sw_little_sw.folio.sync.internal

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/**
 * Polls every `folio.sync.poll-interval` and sweeps the cache every `folio.sync.interval`, both first after one delay
 * (ADR 0032). Registered here rather than with `@Scheduled`, so the delays come from the validated [SyncProperties]
 * with their defaults instead of repeating them in placeholders.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SyncScheduling(
    private val poller: SyncPoller,
    private val cleanup: CacheCleanup,
    private val properties: SyncProperties,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addFixedDelayTask(FixedDelayTask(poller::poll, properties.pollInterval, properties.pollInterval))
        registrar.addFixedDelayTask(FixedDelayTask(cleanup::sweep, properties.interval, properties.interval))
    }
}
