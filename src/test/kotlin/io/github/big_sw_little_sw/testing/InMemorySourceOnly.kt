package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.content.ConfigSource
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.util.ClassUtils

/**
 * Makes [InMemorySource] the only [ConfigSource] of the context. Content needs exactly one adapter for each
 * Source type, and [InMemorySource] registers for the type of the Git adapter. Thus this post-processor removes the
 * definitions of the other adapters before Spring creates beans. Tests in this context publish revisions through
 * [InMemorySource] and need no Git server. [GitIntegrationTest] keeps the Git adapter.
 */
class InMemorySourceOnly : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        registry.beanDefinitionNames
            .filter { name -> isOtherSource(registry.getBeanDefinition(name).beanClassName) }
            .forEach(registry::removeBeanDefinition)
    }

    private fun isOtherSource(className: String?): Boolean {
        if (className == null || className == InMemorySource::class.java.name) return false
        val type = ClassUtils.resolveClassName(className, javaClass.classLoader)
        return ConfigSource::class.java.isAssignableFrom(type)
    }
}
