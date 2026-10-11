package io.github.big_sw_little_sw.folio.git.internal

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/**
 * The schedule of sync (design 10.5, 10.6). Every `folio.sync.poll-interval`, the poller claims due ConfigSets. Every
 * `folio.sync.interval`, the cache sweep deletes the repositories of ConfigSets that no longer exist. Each task first
 * runs after one delay. The delays come from the validated [SyncProperties]. Thus no `@Scheduled` placeholder repeats a
 * default of the properties.
 *
 * Spring runs all fixed-delay tasks on one thread, so a task that waits long also stops the polls. Thus the sweep does
 * not wait for reads ([RepositoryCache.sweep]).
 *
 * The integration tests remove this bean and run polls and sweeps themselves. Thus a scheduled poll never claims the
 * ConfigSets of a test.
 */
@Configuration(proxyBeanMethods = false)
class SyncSchedule(
    private val poller: SyncPoller,
    private val cache: RepositoryCache,
    private val syncs: SyncRepository,
    private val properties: SyncProperties,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addFixedDelayTask(FixedDelayTask(poller::poll, properties.pollInterval, properties.pollInterval))
        registrar.addFixedDelayTask(
            FixedDelayTask({ cache.sweep(syncs::existing) }, properties.interval, properties.interval),
        )
    }
}
