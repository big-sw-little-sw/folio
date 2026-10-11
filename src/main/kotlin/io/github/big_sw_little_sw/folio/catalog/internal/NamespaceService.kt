package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** A Namespace with its path. */
data class Namespace(
    val id: NamespaceId,
    val parentId: NamespaceId?,
    val slug: Slug,
    val description: String?,
    val path: Path,
)

/** A Namespace with its ancestry and the children that the caller can READ. */
data class NamespaceDetails(
    val namespace: Namespace,
    val ancestry: Ancestry,
    val namespaces: List<Namespace>,
    val configSets: List<ConfigSet>,
)

/**
 * The Namespace operations of the HTTP routes and the UI pages (design 5, 7.3).
 *
 * Each operation finds the resource first (404), then authorizes (403 or 401). Each write takes the tree lock first,
 * and records an audit entry only when it changes something. The root is not a resource, so only super admins act at
 * the top level.
 */
@Service
class NamespaceService(
    private val tree: TreeRepository,
    private val namespaces: NamespaceRepository,
    private val configSets: ConfigSetRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    /** The top-level Namespaces that the caller can READ. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun topLevel(caller: Caller): List<Namespace> = readableChildren(caller, Ancestry.ROOT, null)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(caller: Caller, id: NamespaceId): NamespaceDetails {
        val ancestry = ancestry(id)
        authorizer.require(caller, Action.READ, ancestry)
        val namespace = namespace(id, ancestry)
        val rows = configSets.inNamespace(id)
        val readable = authorizer.allowedChildren(caller, Action.READ, ancestry, rows.map { it.id })
        return NamespaceDetails(
            namespace = namespace,
            ancestry = ancestry,
            namespaces = readableChildren(caller, ancestry, id),
            configSets =
            rows
                .filter { it.id in readable }
                .map { it.toConfigSet(ancestry.path + it.slug) },
        )
    }

    /** Creates a Namespace in [parentId], or at the top level if it is null. */
    @Transactional
    fun create(caller: Caller, parentId: NamespaceId?, slug: Slug, description: String?): Namespace {
        val text = validDescription(description)
        tree.lock()
        val parent = parentId?.let(::ancestry) ?: Ancestry.ROOT
        authorizer.require(caller, Action.MANAGE, parent)
        val id = namespaces.insert(parentId, slug, text)
        val namespace = Namespace(id, parentId, slug, text, parent.childPath(slug))
        audit.created(
            caller,
            id,
            namespace.path,
            mapOf("parentId" to parentId?.toApiId(), "description" to text),
        )
        return namespace
    }

    /** Renames, describes or both. */
    @Transactional
    fun edit(caller: Caller, id: NamespaceId, edit: Edit): Namespace {
        tree.lock()
        val ancestry = ancestry(id)
        authorizer.require(caller, Action.MANAGE, ancestry)
        val row = row(id)
        val changed = row.copy(slug = edit.slugOf(row.slug), description = edit.descriptionOf(row.description))
        if (changed != row) {
            namespaces.update(changed)
            audit.edited(
                caller,
                id,
                ancestry,
                changed.slug,
                previousDescription = row.description,
                description = changed.description,
            )
        }
        return Namespace(id, row.parentId, changed.slug, changed.description, ancestry.renamed(changed.slug))
    }

    /**
     * Moves the Namespace into [parentId], or to the top level if it is null. A move needs `MANAGE` on the Namespace
     * and on the new parent. If the Namespace or a resource below it has rules, it also needs `GRANT` on the new
     * parent: the rules then are nearer than the rules of the new parent (design 7.3).
     */
    @Transactional
    fun move(caller: Caller, id: NamespaceId, parentId: NamespaceId?): Namespace {
        tree.lock()
        val ancestry = ancestry(id)
        val target = parentId?.let(::ancestry) ?: Ancestry.ROOT
        authorizer.require(caller, Action.MANAGE, ancestry)
        authorizer.require(caller, Action.MANAGE, target)
        val row = row(id)
        val moved = Namespace(id, parentId, row.slug, row.description, target.childPath(row.slug))
        if (row.parentId == parentId) return moved
        // Under the tree lock, the ancestry of the target cannot change before the update.
        if (id in target.resources) throw CatalogException.MoveIntoOwnSubtree(id)
        if (tree.subtreeHasRules(id)) authorizer.require(caller, Action.GRANT, target)
        namespaces.update(row.copy(parentId = parentId))
        audit.moved(
            caller,
            id,
            moved.path,
            ancestry.path,
            mapOf("parentId" to parentId?.toApiId(), "previousParentId" to row.parentId?.toApiId()),
        )
        return moved
    }

    /** Deletes the Namespace if it is empty. Folio never cascades the delete of a Namespace (design 5.4). */
    @Transactional
    fun delete(caller: Caller, id: NamespaceId) {
        tree.lock()
        val ancestry = ancestry(id)
        authorizer.require(caller, Action.MANAGE, ancestry)
        if (!namespaces.isEmpty(id)) throw CatalogException.NamespaceNotEmpty(id)
        namespaces.delete(id)
        audit.deleted(caller, id, ancestry.path)
    }

    private fun readableChildren(caller: Caller, parent: Ancestry, parentId: NamespaceId?): List<Namespace> {
        val rows = namespaces.children(parentId)
        val readable = authorizer.allowedChildren(caller, Action.READ, parent, rows.map { it.id })
        return rows
            .filter { it.id in readable }
            .map { Namespace(it.id, parentId, it.slug, it.description, parent.childPath(it.slug)) }
    }

    private fun ancestry(id: NamespaceId): Ancestry = tree.ancestry(id) ?: throw CatalogException.NamespaceNotFound(id)

    private fun row(id: NamespaceId): NamespaceRow = namespaces.find(id) ?: throw CatalogException.NamespaceNotFound(id)

    private fun namespace(id: NamespaceId, ancestry: Ancestry): Namespace {
        val row = row(id)
        return Namespace(id, row.parentId, row.slug, row.description, ancestry.path)
    }
}
