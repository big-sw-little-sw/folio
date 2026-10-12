package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.Ancestry
import io.github.big_sw_little_sw.folio.catalog.internal.Authorizer
import io.github.big_sw_little_sw.folio.catalog.internal.CatalogAudit
import io.github.big_sw_little_sw.folio.catalog.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.catalog.internal.validDescription
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Other modules create, find and authorize ConfigSets through this class. The HTTP routes of the catalog do the other
 * ConfigSet operations.
 */
@Service
class ConfigSets(
    private val tree: TreeRepository,
    private val configSetRepository: ConfigSetRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    /**
     * Creates the ConfigSet. The caller needs `MANAGE` on [namespace]. The adapter writes its Source definition in the
     * same transaction, and the adapter authorizes the use of its credential.
     */
    @Transactional
    fun create(
        caller: Caller,
        namespace: NamespaceId,
        slug: Slug,
        description: String?,
        sourceType: SourceType,
    ): ConfigSet {
        val text = validDescription(description)
        val ancestry = authorizer.authorize(caller, Action.MANAGE, namespace)
        val id = configSetRepository.insert(namespace, slug, text, sourceType)
        val configSet = ConfigSet(id, namespace, slug, text, sourceType, ancestry.childPath(slug))
        audit.created(caller, configSet)
        return configSet
    }

    /**
     * Finds a ConfigSet without authorization. One snapshot keeps the row and its path consistent. Without the
     * snapshot, a parallel move could give a path that does not match the row.
     */
    @SnapshotRead
    fun find(id: ConfigSetId): ConfigSet? {
        val row = configSetRepository.find(id) ?: return null
        val ancestry = tree.ancestry(id) ?: return null
        return row.toConfigSet(ancestry.path)
    }

    /**
     * Finds the ConfigSet (404) and then requires [action] on it (403 or 401). In a write, the method first takes
     * [TreeRepository.lockShared].
     */
    @SnapshotRead
    fun require(caller: Caller, action: Action, id: ConfigSetId): ConfigSet = authorized(caller, action, id).first

    /**
     * Finds the ConfigSet (404) and then requires `READ` on it (403 or 401). [ReadableConfigSet.public] comes from the
     * same snapshot, so the ConfigSet, the decision and the public flag agree. The snapshot ends when this method
     * returns.
     */
    @SnapshotRead
    fun requireReadable(caller: Caller, id: ConfigSetId): ReadableConfigSet {
        val (configSet, ancestry) = authorized(caller, Action.READ, id)
        val public = authorizer.decide(Caller.Anonymous, Action.READ, ancestry).allowed
        return ReadableConfigSet(configSet, public)
    }

    private fun authorized(caller: Caller, action: Action, id: ConfigSetId): Pair<ConfigSet, Ancestry> {
        tree.lockShared()
        val ancestry = tree.requireAncestry(id)
        authorizer.require(caller, action, ancestry)
        val row = configSetRepository.find(id) ?: throw CatalogException.ConfigSetNotFound(id)
        return row.toConfigSet(ancestry.path) to ancestry
    }
}
