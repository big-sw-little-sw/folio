package io.github.big_sw_little_sw.folio.git.internal

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/**
 * Schedules the poll and the cache sweep. The delays come from the validated [SyncProperties], so no `@Scheduled`
 * placeholder repeats a default of the properties.
 *
 * Spring runs all fixed-delay tasks on one thread. Thus a task that waits long also stops the polls, and no task may
 * wait for reads.
 */
@Configuration(proxyBeanMethods = false)
class SyncSchedule(
    private val poller: SyncPoller,
    private val cache: RepositoryCache,
    private val syncRepository: SyncRepository,
    private val properties: SyncProperties,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addFixedDelayTask(FixedDelayTask(poller::poll, properties.pollInterval, properties.pollInterval))
        registrar.addFixedDelayTask(
            FixedDelayTask({ cache.sweep(syncRepository::existing) }, properties.interval, properties.interval),
        )
    }
}
