package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.git.internal.SyncSchedule
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor

/**
 * Removes the schedule of sync from the test context, before Spring creates beans. Without this removal, a scheduled
 * poll would claim and sync the ConfigSets of a test while the test runs. A scheduled sweep would also delete cached
 * repositories that a test prepared. The tests run polls, syncs and sweeps themselves. `bootTestRun` does not use this
 * class, so a local run keeps the schedule.
 */
class NoSyncSchedule : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        registry.beanDefinitionNames
            .filter { registry.getBeanDefinition(it).beanClassName == SyncSchedule::class.java.name }
            .forEach(registry::removeBeanDefinition)
    }
}
