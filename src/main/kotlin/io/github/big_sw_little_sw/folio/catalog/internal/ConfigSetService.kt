package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A ConfigSet with its ancestry. [readable] has the links of the ancestry that the caller can READ, as in
 * [ResourceSummary].
 */
data class ConfigSetDetails(val configSet: ConfigSet, val ancestry: Ancestry, val readable: Set<ResourceId>)

/**
 * The ConfigSet operations of the HTTP routes and the UI pages, except create. The routes of each Source create its
 * ConfigSets through [ConfigSets.create] (design 12.2). The order of the checks and the tree lock are the same as in
 * [NamespaceService].
 */
@Service
class ConfigSetService(
    private val tree: TreeRepository,
    private val configSets: ConfigSetRepository,
    private val rules: RuleRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    @SnapshotRead
    fun get(caller: Caller, id: ConfigSetId): ConfigSetDetails {
        val ancestry = tree.requireAncestry(id)
        authorizer.require(caller, Action.READ, ancestry)
        return ConfigSetDetails(
            row(id).toConfigSet(ancestry.path),
            ancestry,
            authorizer.readable(caller, listOf(ancestry)),
        )
    }

    /**
     * Finds the ConfigSet at [path]. A person can guess a path, so the response does not tell which paths exist (design
     * 7.4). An anonymous caller gets the same 401 for a path that does not exist and for a path that the caller cannot
     * READ. Other callers get the same 404 for both.
     */
    @SnapshotRead
    fun resolve(caller: Caller, path: String): ConfigSet {
        val parsed = Path.parse(path)
        if (parsed.slugs.size < 2) throw CatalogException.InvalidPath()
        val id = tree.resolve(parsed)
        val ancestry = id?.let(tree::ancestry)
        val row = id?.let(configSets::find)
        if (ancestry == null || row == null || !authorizer.decide(caller, Action.READ, ancestry).allowed) {
            throw when (caller) {
                Caller.Anonymous -> CatalogException.Unauthenticated(Action.READ)
                is Caller.Authenticated, Caller.System -> CatalogException.PathNotFound()
            }
        }
        return row.toConfigSet(ancestry.path)
    }

    /** Renames, describes or both. */
    @Transactional
    fun edit(caller: Caller, id: ConfigSetId, edit: Edit): ConfigSet {
        tree.lock()
        val ancestry = tree.requireAncestry(id)
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
     * Moves the ConfigSet into [namespaceId]. The caller needs `MANAGE` on the ConfigSet and on the Namespace. If the
     * ConfigSet has its own rules, the caller also needs `GRANT` on the Namespace. Without that check, a caller could
     * move rules into a Namespace and lock out its administrators (design 7.3).
     */
    @Transactional
    fun move(caller: Caller, id: ConfigSetId, namespaceId: NamespaceId): ConfigSet {
        tree.lock()
        val ancestry = tree.requireAncestry(id)
        val target = tree.requireAncestry(namespaceId)
        authorizer.require(caller, Action.MANAGE, ancestry)
        authorizer.require(caller, Action.MANAGE, target)
        val row = row(id)
        val moved = row.copy(namespaceId = namespaceId)
        if (row.namespaceId == namespaceId) return row.toConfigSet(ancestry.path)
        if (rules.anyOn(id)) authorizer.require(caller, Action.GRANT, target)
        configSets.update(moved)
        val configSet = moved.toConfigSet(target.childPath(row.slug))
        audit.moved(caller, ancestry, configSet, previousNamespaceId = row.namespaceId)
        return configSet
    }

    /**
     * Deletes the ConfigSet and its rules. Foreign keys that cascade delete the Source definition and the revisions of
     * the ConfigSet. After the delete, consumers get status 404 (design 5.4).
     */
    @Transactional
    fun delete(caller: Caller, id: ConfigSetId) {
        tree.lock()
        val ancestry = tree.requireAncestry(id)
        authorizer.require(caller, Action.MANAGE, ancestry)
        configSets.delete(id)
        audit.deleted(caller, id, ancestry.path)
    }

    private fun row(id: ConfigSetId): ConfigSetRow = configSets.find(id) ?: throw CatalogException.ConfigSetNotFound(id)
}
