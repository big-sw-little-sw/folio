package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.namespace.internal.NamespaceClosureRepository
import io.github.big_sw_little_sw.folio.namespace.internal.NamespaceRepository
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Expected failures are thrown as [NamespaceException] subtypes (ADR 0006), and denials as policy exceptions.
 * A missing namespace fails before authorization, so it gives not found rather than denied (ADR 0010).
 * Every write takes the tree lock first; see [NamespaceTree.lock].
 */
@Service
class NamespaceService(
    private val namespaces: NamespaceRepository,
    private val closure: NamespaceClosureRepository,
    private val tree: NamespaceTree,
    private val policy: PolicyService,
    private val events: ApplicationEventPublisher,
) {
    /** Creates a namespace under [parentId], or at the root if it is null. Requires create on the parent. */
    @Transactional
    fun create(
        parentId: NamespaceId?,
        slug: Slug,
    ): Namespace {
        tree.lock()
        policy.requireAllowed(Action.NAMESPACE_CREATE, pathOrRoot(parentId))
        val namespace = namespaces.insert(parentId, slug)
        closure.insertPathsForLeaf(namespace.id, parentId)
        return namespace
    }

    @Transactional
    fun rename(
        id: NamespaceId,
        slug: Slug,
    ): Namespace {
        tree.lock()
        val renamed = existing(id).copy(slug = slug)
        policy.requireAllowed(Action.NAMESPACE_RENAME, path(id))
        namespaces.update(renamed)
        return renamed
    }

    /**
     * Moves the namespace and its subtree under [newParentId], or to the root if it is null.
     * Requires move on the namespace and create on the new parent, as if creating it there. If the subtree's
     * namespaces have rules of their own, also requires policy update on the new parent; listeners of
     * [NamespaceMoving] check the resources in them the same way (ADR 0031).
     */
    @Transactional
    fun move(
        id: NamespaceId,
        newParentId: NamespaceId?,
    ): Namespace {
        tree.lock()
        val moved = existing(id).copy(parentId = newParentId)
        val targetPath = pathOrRoot(newParentId)
        policy.requireAllowed(Action.NAMESPACE_MOVE, path(id))
        policy.requireAllowed(Action.NAMESPACE_CREATE, targetPath)
        if (newParentId != null && closure.isAncestorOrSelf(id, newParentId)) {
            throw NamespaceMoveIntoOwnSubtreeException(id, newParentId)
        }
        val subtree = closure.findSubtree(id)
        policy.requireAllowedToMoveRules(subtree.map { ResourceRef.NamespaceRef(it.value) }, targetPath)
        events.publishEvent(NamespaceMoving(subtree, targetPath))
        namespaces.update(moved)
        closure.moveSubtree(id, newParentId)
        return moved
    }

    /**
     * Deletes a namespace and its rules if it holds no namespaces or ConfigSets; there is no cascading delete
     * of contents (ADR 0001).
     */
    @Transactional
    fun delete(id: NamespaceId) {
        tree.lock()
        policy.requireAllowed(Action.NAMESPACE_DELETE, path(id))
        if (namespaces.hasChildren(id)) throw NamespaceNotEmptyException(id)
        closure.deletePathsForLeaf(id)
        namespaces.delete(id)
    }

    @Transactional(readOnly = true)
    fun get(id: NamespaceId): Namespace {
        val namespace = existing(id)
        policy.requireAllowed(Action.NAMESPACE_VIEW, path(id))
        return namespace
    }

    /** Children of [parentId], or the root namespaces if it is null, that the caller may view; ordered by slug. */
    @Transactional(readOnly = true)
    fun children(parentId: NamespaceId?): List<Namespace> {
        val parentPath = pathOrRoot(parentId)
        return namespaces
            .findChildren(parentId)
            .filter { policy.isAllowed(Action.NAMESPACE_VIEW, parentPath + ResourceRef.NamespaceRef(it.id.value)) }
    }

    /**
     * Ancestors of [id], root first, without the namespace itself. Requires view on the namespace only:
     * seeing a namespace includes seeing its path (ADR 0010).
     */
    @Transactional(readOnly = true)
    fun ancestors(id: NamespaceId): List<Namespace> {
        get(id)
        return namespaces.findAncestors(id)
    }

    private fun existing(id: NamespaceId): Namespace = namespaces.findById(id) ?: throw NamespaceNotFoundException(id)

    private fun path(id: NamespaceId): List<ResourceRef> = tree.policyPath(id)

    /** The policy path of [id], or of the root if it is null. */
    private fun pathOrRoot(id: NamespaceId?): List<ResourceRef> = id?.let(::path) ?: emptyList()
}
