package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.configset.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.NamespaceMoving
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotFoundException
import io.github.big_sw_little_sw.folio.namespace.NamespaceTree
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceCheck
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
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
    private val credentials: CredentialService,
    private val sources: SourceAccess,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Requires create on the namespace (ADR 0014), and use of the source's credential, which must be enabled
     * (ADR 0023). Does not contact the Git service; [check] does.
     */
    @Transactional
    fun create(
        namespaceId: NamespaceId,
        slug: Slug,
        source: SourceDefinition,
    ): ConfigSet {
        tree.lock()
        policy.requireAllowed(Action.CONFIG_SET_CREATE, tree.policyPath(namespaceId))
        credentials.requireUsable(source.credentialId)
        val configSet = configSets.insert(namespaceId, slug, source)
        events.publishEvent(ConfigSetCreated(configSet.id))
        return configSet
    }

    /**
     * The onboarding check (ADR 0027): whether the source's credential reaches the repository, the branch exists and
     * the root path is a directory at its tip. With [keyId], checks that pending key of the credential instead of the
     * active one. Requires view on the ConfigSet and use of its credential (ADR 0023).
     *
     * Deliberately not transactional: it talks to the Git service, which must not hold a database connection.
     */
    fun check(
        id: ConfigSetId,
        keyId: KeyId?,
    ): SourceCheck {
        val configSet = existing(id)
        policy.requireAllowed(Action.CONFIG_SET_VIEW, path(configSet))
        credentials.requireUsable(configSet.source.credentialId)
        return sources.check(id.value, configSet.source, keyId)
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

    /**
     * Requires move on the ConfigSet and create in the target namespace, as if creating it there (ADR 0014). If the
     * ConfigSet has rules of its own, also requires policy update on the target namespace (ADR 0031).
     */
    @Transactional
    fun move(
        id: ConfigSetId,
        namespaceId: NamespaceId,
    ): ConfigSet {
        tree.lock()
        val configSet = existing(id)
        val targetPath = tree.policyPath(namespaceId)
        policy.requireAllowed(Action.CONFIG_SET_MOVE, path(configSet))
        policy.requireAllowed(Action.CONFIG_SET_CREATE, targetPath)
        policy.requireAllowedToMoveRules(listOf(ResourceRef.ConfigSetRef(id.value)), targetPath)
        val moved = configSet.copy(namespaceId = namespaceId)
        configSets.update(moved)
        return moved
    }

    /** The ConfigSets in a moving namespace subtree carry their own rules along, as on a ConfigSet move (ADR 0031). */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onNamespaceMoving(event: NamespaceMoving) {
        val moved = configSets.findIdsByNamespaces(event.subtree).map { ResourceRef.ConfigSetRef(it.value) }
        policy.requireAllowedToMoveRules(moved, event.targetPath)
    }

    /** Deletes the ConfigSet, its rules and its sync state. */
    @Transactional
    fun delete(id: ConfigSetId) {
        tree.lock()
        policy.requireAllowed(Action.CONFIG_SET_DELETE, path(existing(id)))
        configSets.delete(id)
        events.publishEvent(ConfigSetDeleted(id))
    }

    @Transactional(readOnly = true)
    fun get(id: ConfigSetId): ConfigSet = requireAllowed(id, Action.CONFIG_SET_VIEW)

    /**
     * Requires [action] on the ConfigSet, for modules that act on ConfigSets, such as sync. Throws
     * [ConfigSetNotFoundException] first if it does not exist (ADR 0010).
     */
    @Transactional(readOnly = true)
    fun requireAllowed(
        id: ConfigSetId,
        action: Action,
    ): ConfigSet {
        val configSet = existing(id)
        policy.requireAllowed(action, path(configSet))
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

    /**
     * The ConfigSet at [path]. Requires view on it, which includes seeing its path. Paths can be guessed, so a
     * ConfigSet the caller may not view is reported as not found, unlike lookups by ID (ADR 0013).
     */
    @Transactional(readOnly = true)
    fun resolve(path: ConfigSetPath): ConfigSet {
        val namespace = tree.findByPath(path.namespacePath) ?: throw ConfigSetPathNotFoundException(path)
        val configSet = configSets.findBySlug(namespace.id, path.slug)
        if (configSet == null || !policy.isAllowed(Action.CONFIG_SET_VIEW, path(configSet))) {
            throw ConfigSetPathNotFoundException(path)
        }
        return configSet
    }

    private fun existing(id: ConfigSetId): ConfigSet = configSets.findById(id) ?: throw ConfigSetNotFoundException(id)

    /** The policy path of [configSet]: its namespaces, then the ConfigSet itself. */
    private fun path(configSet: ConfigSet): List<ResourceRef> =
        tree.policyPath(configSet.namespaceId) + ResourceRef.ConfigSetRef(configSet.id.value)
}
