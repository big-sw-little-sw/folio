package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.namespace.internal.NamespaceClosureRepository
import io.github.big_sw_little_sw.folio.namespace.internal.NamespaceRepository
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The namespace tree for modules that place resources in namespaces, such as ConfigSets (ADR 0015).
 * Nothing here authorizes: callers check policy on the paths it returns.
 */
@Service
class NamespaceTree(
    private val namespaces: NamespaceRepository,
    private val closure: NamespaceClosureRepository,
) {
    /**
     * Takes the tree lock until the caller's transaction ends; see [NamespaceClosureRepository.lockTree].
     * Writes that depend on a namespace's existence or path call this first, so namespace moves and deletes
     * wait for them. The lock is useless outside a transaction, hence MANDATORY.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock() {
        closure.lockTree()
    }

    /**
     * The policy path of [id]: its namespaces from the root namespace down to [id] itself.
     * Throws [NamespaceNotFoundException] if [id] does not exist.
     */
    @Transactional(readOnly = true)
    fun policyPath(id: NamespaceId): List<ResourceRef> = closure.findPath(id).map { ResourceRef.NamespaceRef(it.value) }

    /**
     * The slugs of [id]'s namespaces from the root namespace down to [id] itself. Throws [NamespaceNotFoundException]
     * if [id] does not exist.
     */
    @Transactional(readOnly = true)
    fun slugPath(id: NamespaceId): List<Slug> {
        val namespace = namespaces.findById(id) ?: throw NamespaceNotFoundException(id)
        return namespaces.findAncestors(id).map { it.slug } + namespace.slug
    }

    /** The namespace reached by following [slugs] from the root, or null if there is none. */
    @Transactional(readOnly = true)
    fun findByPath(slugs: List<Slug>): Namespace? {
        var namespace: Namespace? = null
        for (slug in slugs) {
            namespace = namespaces.findChild(namespace?.id, slug) ?: return null
        }
        return namespace
    }
}
