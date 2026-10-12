package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A ConfigSet with the [ResourceSummary] that its page shows. */
data class ConfigSetDetails(val configSet: ConfigSet, val summary: ResourceSummary)

/**
 * The ConfigSet operations of the HTTP routes and the UI pages, except create. Each Source creates its ConfigSets
 * through [ConfigSets.create].
 */
@Service
class ConfigSetService(
    private val tree: TreeRepository,
    private val configSetRepository: ConfigSetRepository,
    private val ruleRepository: RuleRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    @SnapshotRead
    fun get(caller: Caller, id: ConfigSetId): ConfigSetDetails {
        val ancestry = authorizer.authorize(caller, Action.READ, id)
        val configSet = row(id).toConfigSet(ancestry.path)
        return ConfigSetDetails(
            configSet,
            ResourceSummary(ancestry, configSet.description, authorizer.readable(caller, listOf(ancestry))),
        )
    }

    /**
     * Finds the ConfigSet at [path]. A person can guess a path, so the response does not tell which paths exist. An
     * anonymous caller gets the same 401 for a path that does not exist and for a path that the caller cannot READ.
     * Other callers get the same 404 for both.
     */
    @SnapshotRead
    fun resolve(caller: Caller, path: String): ConfigSet {
        val parsed = Path.parse(path)
        if (parsed.slugs.size < 2) throw CatalogException.InvalidPath()
        val id = tree.resolve(parsed)
        val ancestry = id?.let(tree::ancestry)
        val row = id?.let(configSetRepository::find)
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
        val ancestry = authorizer.authorize(caller, Action.MANAGE, id)
        val row = row(id)
        val changed = row.copy(slug = edit.slugOf(row.slug), description = edit.descriptionOf(row.description))
        if (changed != row) {
            configSetRepository.update(changed)
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
     * ConfigSet has its own rules, the caller also needs `GRANT` on the Namespace, as in [NamespaceService.move].
     *
     * [move] finds the ConfigSet and the target Namespace before it authorizes either one. Thus a missing Namespace
     * gives 404, not 403, and [move] cannot use [Authorizer.authorize].
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
        if (ruleRepository.anyOn(id)) authorizer.require(caller, Action.GRANT, target)
        configSetRepository.update(moved)
        val configSet = moved.toConfigSet(target.childPath(row.slug))
        audit.moved(caller, ancestry, configSet, previousNamespaceId = row.namespaceId)
        return configSet
    }

    /** Deletes the ConfigSet with its rules, Source definition and revisions. Consumers then get status 404. */
    @Transactional
    fun delete(caller: Caller, id: ConfigSetId) {
        val ancestry = authorizer.authorize(caller, Action.MANAGE, id)
        configSetRepository.delete(id)
        audit.deleted(caller, id, ancestry.path)
    }

    private fun row(id: ConfigSetId): ConfigSetRow =
        configSetRepository.find(id) ?: throw CatalogException.ConfigSetNotFound(id)
}
