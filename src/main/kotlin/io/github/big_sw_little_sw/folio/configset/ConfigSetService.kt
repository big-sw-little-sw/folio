package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.configset.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotFoundException
import io.github.big_sw_little_sw.folio.namespace.NamespaceTree
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Expected failures are thrown as [ConfigSetException] subtypes (ADR 0006), a missing namespace as
 * [NamespaceNotFoundException], and denials as policy exceptions. Lookups come before authorization, so a
 * missing resource gives not found rather than denied (ADR 0010).
 *
 * Every write takes the namespace tree lock first (ADR 0015). A namespace move or delete then cannot run
 * between reading a ConfigSet's path, authorizing against it and writing.
 */
@Service
class ConfigSetService(
    private val configSets: ConfigSetRepository,
    private val tree: NamespaceTree,
    private val policy: PolicyService,
) {
    /** Requires create on the namespace (ADR 0014). */
    @Transactional
    fun create(
        namespaceId: NamespaceId,
        slug: Slug,
    ): ConfigSet {
        tree.lock()
        policy.requireAllowed(Action.CONFIG_SET_CREATE, tree.policyPath(namespaceId))
        return configSets.insert(namespaceId, slug)
    }

    @Transactional
    fun rename(
        id: ConfigSetId,
        slug: Slug,
    ): ConfigSet {
        tree.lock()
        val renamed = existing(id).copy(slug = slug)
        policy.requireAllowed(Action.CONFIG_SET_RENAME, path(renamed))
        configSets.update(renamed)
        return renamed
    }

    /** Requires move on the ConfigSet and create in the target namespace, as if creating it there (ADR 0014). */
    @Transactional
    fun move(
        id: ConfigSetId,
        namespaceId: NamespaceId,
    ): ConfigSet {
        tree.lock()
        val configSet = existing(id)
        policy.requireAllowed(Action.CONFIG_SET_MOVE, path(configSet))
        policy.requireAllowed(Action.CONFIG_SET_CREATE, tree.policyPath(namespaceId))
        val moved = configSet.copy(namespaceId = namespaceId)
        configSets.update(moved)
        return moved
    }

    /** Deletes the ConfigSet and its rules. */
    @Transactional
    fun delete(id: ConfigSetId) {
        tree.lock()
        policy.requireAllowed(Action.CONFIG_SET_DELETE, path(existing(id)))
        configSets.delete(id)
    }

    @Transactional(readOnly = true)
    fun get(id: ConfigSetId): ConfigSet {
        val configSet = existing(id)
        policy.requireAllowed(Action.CONFIG_SET_VIEW, path(configSet))
        return configSet
    }

    /** ConfigSets in [namespaceId] that the caller may view; ordered by slug. */
    @Transactional(readOnly = true)
    fun list(namespaceId: NamespaceId): List<ConfigSet> {
        val namespacePath = tree.policyPath(namespaceId)
        return configSets
            .findByNamespace(namespaceId)
            .filter { policy.isAllowed(Action.CONFIG_SET_VIEW, namespacePath + ResourceRef.ConfigSetRef(it.id.value)) }
    }

    /** The ConfigSet at [path]. Requires view on it, which includes seeing its path (ADR 0013). */
    @Transactional(readOnly = true)
    fun resolve(path: ConfigSetPath): ConfigSet {
        val configSet =
            tree.findByPath(path.namespacePath)?.let { configSets.findBySlug(it.id, path.slug) }
                ?: throw ConfigSetPathNotFoundException(path)
        policy.requireAllowed(Action.CONFIG_SET_VIEW, path(configSet))
        return configSet
    }

    private fun existing(id: ConfigSetId): ConfigSet = configSets.findById(id) ?: throw ConfigSetNotFoundException(id)

    /** The policy path of [configSet]: its namespaces, then the ConfigSet itself. */
    private fun path(configSet: ConfigSet): List<ResourceRef> =
        tree.policyPath(configSet.namespaceId) + ResourceRef.ConfigSetRef(configSet.id.value)
}
