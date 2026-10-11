package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.catalog.ReadableConfigSet
import io.github.big_sw_little_sw.folio.catalog.SourceType
import io.github.big_sw_little_sw.folio.content.ConfigItemInfo
import io.github.big_sw_little_sw.folio.content.ConfigSource
import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.content.Revision
import io.github.big_sw_little_sw.folio.content.RevisionId
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/** What a consumer sees of a ConfigSet. */
data class Metadata(val configSet: ConfigSet, val latest: RevisionId?)

data class RevisionList(val configSet: ConfigSet, val revisions: List<Revision>)

/** For a read of `latest`, [revision] names the exact revision that `latest` selected. */
data class ItemList(val configSet: ConfigSet, val revision: RevisionId, val items: List<ConfigItemInfo>)

/**
 * The reads of the consumption API and the UI. Each read authorizes `READ` with [ConfigSets.requireReadable] and then
 * calls the adapter of the Source type of the ConfigSet.
 *
 * The service has no transaction of its own. Only [ConfigSets.requireReadable] runs in a transaction, and that
 * transaction ends before the adapter call. Without this rule, a slow read or a fetch on demand holds a database
 * connection.
 */
@Service
class ContentService(
    private val configSets: ConfigSets,
    adapters: ObjectProvider<ConfigSource>,
    private val properties: ContentProperties,
) {
    private val sources: Map<SourceType, ConfigSource> = sourcesByType(adapters.orderedStream().toList())

    /** Uses the cache policy of `latest`, because the metadata names the latest revision. */
    fun metadata(caller: Caller, id: ConfigSetId): Cached<Metadata> {
        val readable = configSets.requireReadable(caller, id)
        val configSet = readable.configSet
        val latest = sourceOf(configSet).latest(id)
        val etag =
            digestTag(listOf(configSet.path.toString(), configSet.description.orEmpty(), latest?.value.orEmpty()))
        return Cached(Metadata(configSet, latest), etag, cacheControlOf(readable, RevisionSelector.Latest))
    }

    /**
     * Returns a maximum of [MAX_REVISIONS] revisions in the order of [ConfigSource.revisions]. Older revisions stay
     * readable by their ID. The list uses the cache policy of `latest`, because each new publish changes the list.
     */
    fun revisions(caller: Caller, id: ConfigSetId): Cached<RevisionList> {
        val readable = configSets.requireReadable(caller, id)
        val revisions = sourceOf(readable.configSet).revisions(id, MAX_REVISIONS)
        val etag = digestTag(revisions.map { "${it.id} ${it.publishedAt}" })
        val cacheControl = cacheControlOf(readable, RevisionSelector.Latest)
        return Cached(RevisionList(readable.configSet, revisions), etag, cacheControl)
    }

    /** The ETag is the revision ID, because the file list of a published revision never changes. */
    fun items(caller: Caller, id: ConfigSetId, selector: RevisionSelector): Cached<ItemList> {
        val readable = configSets.requireReadable(caller, id)
        val source = sourceOf(readable.configSet)
        val revision = revisionOf(source, id, selector)
        return Cached(
            ItemList(readable.configSet, revision, source.list(id, revision)),
            revision.value,
            cacheControlOf(readable, selector),
        )
    }

    /** Opens the ConfigItem at [path] and calls [use] while the ConfigItem is open. */
    fun <T> read(
        caller: Caller,
        id: ConfigSetId,
        path: ItemPath,
        selector: RevisionSelector,
        use: (ItemRead) -> T,
    ): T {
        val readable = configSets.requireReadable(caller, id)
        val source = sourceOf(readable.configSet)
        val revision = revisionOf(source, id, selector)
        val cacheControl = cacheControlOf(readable, selector)
        return source.read(id, revision, path, properties.maxFileSize.toBytes()) { item, stream ->
            use(ItemRead(readable.configSet, revision, item, cacheControl, stream))
        }
    }

    private fun cacheControlOf(readable: ReadableConfigSet, selector: RevisionSelector): String =
        cacheControl(selector, readable.public, properties.latestMaxAge)

    /** [sourcesByType] makes sure at startup that each Source type has an adapter, so `getValue` never fails. */
    private fun sourceOf(configSet: ConfigSet): ConfigSource = sources.getValue(configSet.sourceType)

    private fun revisionOf(source: ConfigSource, id: ConfigSetId, selector: RevisionSelector): RevisionId =
        when (selector) {
            RevisionSelector.Latest -> source.latest(id) ?: throw ContentException.NotPublishedYet(id)
            is RevisionSelector.Exact -> selector.id
        }

    companion object {
        /** The revision list has no pagination, so this limit keeps each response small. */
        const val MAX_REVISIONS = 100
    }
}

/**
 * Maps each Source type to its adapter. Each Source type needs exactly one adapter, so two adapters for one type or a
 * type without an adapter stop the start of Folio.
 */
fun sourcesByType(adapters: List<ConfigSource>): Map<SourceType, ConfigSource> {
    val duplicates = adapters.groupBy { it.type }.filterValues { it.size > 1 }.keys
    check(duplicates.isEmpty()) { "More than one ConfigSource adapter for $duplicates" }
    val missing = SourceType.entries - adapters.map { it.type }.toSet()
    check(missing.isEmpty()) { "No ConfigSource adapter for $missing" }
    return adapters.associateBy { it.type }
}
