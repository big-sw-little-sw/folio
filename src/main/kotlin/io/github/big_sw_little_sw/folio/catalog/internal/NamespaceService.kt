package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.Namespace
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A Namespace with the [ResourceSummary] that its page shows and the children that the caller can READ. */
data class NamespaceDetails(
    val namespace: Namespace,
    val summary: ResourceSummary,
    val namespaces: List<Namespace>,
    val configSets: List<ConfigSet>,
)

/**
 * The Namespace operations of the HTTP routes and the UI pages. [create] and [move] accept the root as the parent, and
 * the root has no ID. Thus [create] and [move] cannot use [Authorizer.authorize].
 */
@Service
class NamespaceService(
    private val tree: TreeRepository,
    private val namespaceRepository: NamespaceRepository,
    private val configSetRepository: ConfigSetRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    /** The top-level Namespaces that the caller can READ. */
    @SnapshotRead
    fun topLevel(caller: Caller): List<Namespace> = readableChildren(caller, Ancestry.ROOT, null)

    @SnapshotRead
    fun get(caller: Caller, id: NamespaceId): NamespaceDetails {
        val ancestry = authorizer.authorize(caller, Action.READ, id)
        val namespace = row(id).toNamespace(ancestry.path)
        val rows = configSetRepository.inNamespace(id)
        val readable = authorizer.readable(caller, ancestry, rows.map { it.id })
        return NamespaceDetails(
            namespace = namespace,
            summary = ResourceSummary(ancestry, namespace.description, authorizer.readable(caller, listOf(ancestry))),
            namespaces = readableChildren(caller, ancestry, id),
            configSets =
            rows
                .filter { it.id in readable }
                .map { it.toConfigSet(ancestry.childPath(it.slug)) },
        )
    }

    /** Creates a Namespace in [parentId], or at the top level if [parentId] is null. The root needs a super admin. */
    @Transactional
    fun create(caller: Caller, parentId: NamespaceId?, slug: Slug, description: String?): Namespace {
        val text = validDescription(description)
        tree.lock()
        val parent = parentId?.let(tree::requireAncestry) ?: Ancestry.ROOT
        authorizer.require(caller, Action.MANAGE, parent)
        val id = namespaceRepository.insert(parentId, slug, text)
        val namespace = Namespace(id, parentId, slug, text, parent.childPath(slug))
        audit.created(caller, namespace)
        return namespace
    }

    /** Renames, describes or both. */
    @Transactional
    fun edit(caller: Caller, id: NamespaceId, edit: Edit): Namespace {
        val ancestry = authorizer.authorize(caller, Action.MANAGE, id)
        val row = row(id)
        val changed = row.copy(slug = edit.slugOf(row.slug), description = edit.descriptionOf(row.description))
        if (changed != row) {
            namespaceRepository.update(changed)
            audit.edited(
                caller,
                id,
                ancestry,
                changed.slug,
                previousDescription = row.description,
                description = changed.description,
            )
        }
        return changed.toNamespace(ancestry.renamed(changed.slug))
    }

    /**
     * Moves the Namespace into [parentId], or to the top level if [parentId] is null. The caller needs `MANAGE` on the
     * Namespace and on the new parent. If the Namespace or a resource below it has its own rules, the caller also needs
     * `GRANT` on the new parent. After the move, those rules are nearer than the rules of the new parent. Without the
     * `GRANT` check, a caller could lock out the administrators of the new parent.
     *
     * [move] finds the Namespace and the target before it authorizes either one, so a missing target gives 404, not
     * 403.
     */
    @Transactional
    fun move(caller: Caller, id: NamespaceId, parentId: NamespaceId?): Namespace {
        tree.lock()
        val ancestry = tree.requireAncestry(id)
        val target = parentId?.let(tree::requireAncestry) ?: Ancestry.ROOT
        authorizer.require(caller, Action.MANAGE, ancestry)
        authorizer.require(caller, Action.MANAGE, target)
        val row = row(id)
        val movedRow = row.copy(parentId = parentId)
        val moved = movedRow.toNamespace(target.childPath(row.slug))
        if (row.parentId == parentId) return moved
        // The tree lock keeps the ancestry of the target fixed until the update, so this check prevents a cycle.
        if (id in target.resources) throw CatalogException.MoveIntoOwnSubtree(id)
        if (tree.subtreeHasRules(id)) authorizer.require(caller, Action.GRANT, target)
        namespaceRepository.update(movedRow)
        audit.moved(caller, ancestry, moved, previousParentId = row.parentId)
        return moved
    }

    /**
     * Deletes the Namespace if it is empty. Folio never cascades the delete of a Namespace, because a cascade could
     * delete ConfigSets that consumers use.
     */
    @Transactional
    fun delete(caller: Caller, id: NamespaceId) {
        val ancestry = authorizer.authorize(caller, Action.MANAGE, id)
        if (!namespaceRepository.isEmpty(id)) throw CatalogException.NamespaceNotEmpty(id)
        namespaceRepository.delete(id)
        audit.deleted(caller, id, ancestry.path)
    }

    private fun readableChildren(caller: Caller, parent: Ancestry, parentId: NamespaceId?): List<Namespace> {
        val rows = namespaceRepository.children(parentId)
        val readable = authorizer.readable(caller, parent, rows.map { it.id })
        return rows
            .filter { it.id in readable }
            .map { it.toNamespace(parent.childPath(it.slug)) }
    }

    private fun row(id: NamespaceId): NamespaceRow =
        namespaceRepository.find(id) ?: throw CatalogException.NamespaceNotFound(id)
}
