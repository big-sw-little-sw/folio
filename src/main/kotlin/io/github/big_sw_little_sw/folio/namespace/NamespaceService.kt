package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.namespace.internal.NamespaceClosureRepository
import io.github.big_sw_little_sw.folio.namespace.internal.NamespaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Expected failures are thrown as [NamespaceException] subtypes (ADR 0006).
 * Every write takes the tree lock first; see [NamespaceClosureRepository.lockTree].
 */
@Service
class NamespaceService(
    private val namespaces: NamespaceRepository,
    private val closure: NamespaceClosureRepository,
) {
    /** Creates a namespace under [parentId], or at the root if it is null. */
    @Transactional
    fun create(
        parentId: NamespaceId?,
        slug: Slug,
    ): Namespace {
        closure.lockTree()
        parentId?.let(::existing)
        val namespace = namespaces.insert(parentId, slug)
        closure.insertPathsForLeaf(namespace.id, parentId)
        return namespace
    }

    @Transactional
    fun rename(
        id: NamespaceId,
        slug: Slug,
    ): Namespace {
        closure.lockTree()
        val renamed = existing(id).copy(slug = slug)
        namespaces.update(renamed)
        return renamed
    }

    /** Moves the namespace and its subtree under [newParentId], or to the root if it is null. */
    @Transactional
    fun move(
        id: NamespaceId,
        newParentId: NamespaceId?,
    ): Namespace {
        closure.lockTree()
        val moved = existing(id).copy(parentId = newParentId)
        if (newParentId != null) {
            existing(newParentId)
            if (closure.isAncestorOrSelf(id, newParentId)) throw NamespaceMoveIntoOwnSubtreeException(id, newParentId)
        }
        namespaces.update(moved)
        closure.moveSubtree(id, newParentId)
        return moved
    }

    /** Deletes an empty namespace; there is no cascading delete (ADR 0001). */
    @Transactional
    fun delete(id: NamespaceId) {
        closure.lockTree()
        existing(id)
        if (namespaces.hasChildren(id)) throw NamespaceNotEmptyException(id)
        closure.deletePathsForLeaf(id)
        namespaces.delete(id)
    }

    @Transactional(readOnly = true)
    fun get(id: NamespaceId): Namespace = existing(id)

    /** Children of [parentId], or the root namespaces if it is null; ordered by slug. */
    @Transactional(readOnly = true)
    fun children(parentId: NamespaceId?): List<Namespace> {
        parentId?.let(::existing)
        return namespaces.findChildren(parentId)
    }

    /** Ancestors of [id], root first, without the namespace itself. */
    @Transactional(readOnly = true)
    fun ancestors(id: NamespaceId): List<Namespace> {
        existing(id)
        return namespaces.findAncestors(id)
    }

    private fun existing(id: NamespaceId): Namespace = namespaces.findById(id) ?: throw NamespaceNotFoundException(id)
}
