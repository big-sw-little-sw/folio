package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** A ConfigSet with its ancestry. */
data class ConfigSetDetails(val configSet: ConfigSet, val ancestry: Ancestry)

/**
 * The ConfigSet operations of the HTTP routes and the UI pages, except create: the route of each Source creates its
 * ConfigSets through [ConfigSets.create] (design 12.2). The rules are the same as for Namespaces.
 */
@Service
class ConfigSetService(
    private val tree: TreeRepository,
    private val configSets: ConfigSetRepository,
    private val rules: RuleRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(caller: Caller, id: ConfigSetId): ConfigSetDetails {
        val ancestry = ancestry(id)
        authorizer.require(caller, Action.READ, ancestry)
        return ConfigSetDetails(row(id).toConfigSet(ancestry.path), ancestry)
    }

    /**
     * Finds the ConfigSet at [path]. An authenticated caller gets the same 404 for a path that does not exist and for
     * a path that it cannot READ. An anonymous caller gets the same 401 for both (design 7.4).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun resolve(caller: Caller, path: String): ConfigSet {
        val parsed = Path.parse(path)
        if (parsed.slugs.size < 2) throw CatalogException.InvalidPath()
        val id = tree.resolve(parsed)
        val ancestry = id?.let(tree::ancestry)
        val row = id?.let(configSets::find)
        if (ancestry == null || row == null || !authorizer.decide(caller, Action.READ, ancestry).allowed) {
            throw when (caller) {
                Caller.Anonymous -> CatalogException.Unauthenticated(Action.READ, null)
                is Caller.Authenticated, Caller.System -> CatalogException.PathNotFound()
            }
        }
        return row.toConfigSet(ancestry.path)
    }

    /** Renames, describes or both. */
    @Transactional
    fun edit(caller: Caller, id: ConfigSetId, edit: Edit): ConfigSet {
        tree.lock()
        val ancestry = ancestry(id)
        authorizer.require(caller, Action.MANAGE, ancestry)
        val row = row(id)
        val changed = row.copy(slug = edit.slugOf(row.slug), description = edit.descriptionOf(row.description))
        if (changed != row) {
            configSets.update(changed)
            audit.edited(
                caller,
                id,
                ancestry,
                changed.slug,
                previousDescription = row.description,
                description = changed.description,
            )
        }
        return changed.toConfigSet(ancestry.renamed(changed.slug))
    }

    /**
     * Moves the ConfigSet into [namespaceId]. It needs `MANAGE` on the ConfigSet and on the Namespace, and `GRANT` on
     * the Namespace if the ConfigSet has rules (design 7.3).
     */
    @Transactional
    fun move(caller: Caller, id: ConfigSetId, namespaceId: NamespaceId): ConfigSet {
        tree.lock()
        val ancestry = ancestry(id)
        val target = tree.ancestry(namespaceId) ?: throw CatalogException.NamespaceNotFound(namespaceId)
        authorizer.require(caller, Action.MANAGE, ancestry)
        authorizer.require(caller, Action.MANAGE, target)
        val row = row(id)
        val moved = row.copy(namespaceId = namespaceId)
        if (row.namespaceId == namespaceId) return row.toConfigSet(ancestry.path)
        if (rules.anyOn(id)) authorizer.require(caller, Action.GRANT, target)
        configSets.update(moved)
        val path = target.path + row.slug
        audit.moved(
            caller,
            id,
            path,
            ancestry.path,
            mapOf("namespaceId" to namespaceId.toApiId(), "previousNamespaceId" to row.namespaceId.toApiId()),
        )
        return moved.toConfigSet(path)
    }

    /**
     * Deletes the ConfigSet and its rules. Foreign keys that cascade delete its Source definition and its revisions.
     * After the delete, consumers get status 404 (design 5.4).
     */
    @Transactional
    fun delete(caller: Caller, id: ConfigSetId) {
        tree.lock()
        val ancestry = ancestry(id)
        authorizer.require(caller, Action.MANAGE, ancestry)
        configSets.delete(id)
        audit.deleted(caller, id, ancestry.path)
    }

    private fun ancestry(id: ConfigSetId): Ancestry = tree.ancestry(id) ?: throw CatalogException.ConfigSetNotFound(id)

    private fun row(id: ConfigSetId): ConfigSetRow = configSets.find(id) ?: throw CatalogException.ConfigSetNotFound(id)
}
