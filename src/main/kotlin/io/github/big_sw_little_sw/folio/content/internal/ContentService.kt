package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
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

/** Metadata is what a consumer sees of a ConfigSet. [latest] is null if the ConfigSet has no published revision. */
data class Metadata(val configSet: ConfigSet, val latest: RevisionId?)

/** A RevisionList holds the newest published revisions of a ConfigSet, newest first. */
data class RevisionList(val configSet: ConfigSet, val revisions: List<Revision>)

/**
 * An ItemList holds the ConfigItems at an exact [revision]. For a read of `latest`, [revision] names the revision that
 * `latest` selected.
 */
data class ItemList(val configSet: ConfigSet, val revision: RevisionId, val items: List<ConfigItemInfo>)

/**
 * The reads of the consumption API and the UI (design 8.2). Each read does these steps:
 *
 * 1. Find the ConfigSet, and authorize `READ` ([ReadAccess]).
 * 2. Change `latest` to an exact revision.
 * 3. Select the adapter for the Source type of the ConfigSet, and call it.
 * 4. Add the cache policy. A file read also gives the validation status ([ItemRead]).
 *
 * The service has no transaction of its own. Only [ReadAccess] runs in a transaction. The adapter calls run outside a
 * transaction, so a slow read or a fetch on demand holds no database connection.
 */
@Service
class ContentService(
    private val readAccess: ReadAccess,
    adapters: ObjectProvider<ConfigSource>,
    private val properties: ContentProperties,
) {
    /**
     * Spring gives the adapter beans of the build. Two adapters for one Source type stop the start of Folio. The main
     * code has no adapter until the Git slice (design 20). Thus a Source type without an adapter fails only at the
     * time of a read, with status 500, not at startup.
     */
    private val sources: Map<SourceType, ConfigSource> =
        adapters.orderedStream().toList().let { all ->
            val duplicates = all.groupBy { it.type }.filterValues { it.size > 1 }.keys
            check(duplicates.isEmpty()) { "More than one ConfigSource adapter for $duplicates" }
            all.associateBy { it.type }
        }

    /**
     * The ETag is a digest of the path, the description and the latest revision. The metadata uses the cache policy of
     * `latest`, because the metadata names the latest revision.
     */
    fun metadata(caller: Caller, id: ConfigSetId): Cached<Metadata> {
        val readable = readAccess.require(caller, id)
        val configSet = readable.configSet
        val latest = sourceOf(configSet).latest(id)
        val etag =
            digestTag(listOf(configSet.path.toString(), configSet.description.orEmpty(), latest?.value.orEmpty()))
        return Cached(Metadata(configSet, latest), etag, cacheControlOf(readable, RevisionSelector.Latest))
    }

    /** Returns a maximum of [MAX_REVISIONS] revisions, newest first. Older revisions stay readable by their ID. */
    fun revisions(caller: Caller, id: ConfigSetId): Cached<RevisionList> {
        val readable = readAccess.require(caller, id)
        val revisions = sourceOf(readable.configSet).revisions(id, MAX_REVISIONS)
        val etag = digestTag(revisions.map { "${it.id} ${it.publishedAt}" })
        val cacheControl = cacheControlOf(readable, RevisionSelector.Latest)
        return Cached(RevisionList(readable.configSet, revisions), etag, cacheControl)
    }

    /** The ETag is the revision ID, because the file list of a published revision never changes (design 5.5). */
    fun items(caller: Caller, id: ConfigSetId, selector: RevisionSelector): Cached<ItemList> {
        val readable = readAccess.require(caller, id)
        val source = sourceOf(readable.configSet)
        val revision = revisionOf(source, id, selector)
        return Cached(
            ItemList(readable.configSet, revision, source.list(id, revision)),
            revision.value,
            cacheControlOf(readable, selector),
        )
    }

    /**
     * Opens the ConfigItem at [path] and calls [use] while the ConfigItem is open. The adapter refuses a ConfigItem
     * larger than `folio.content.max-file-size` with ItemTooLarge (422) before it opens the ConfigItem.
     */
    fun <T> read(
        caller: Caller,
        id: ConfigSetId,
        path: ItemPath,
        selector: RevisionSelector,
        use: (ItemRead) -> T,
    ): T {
        val readable = readAccess.require(caller, id)
        val source = sourceOf(readable.configSet)
        val revision = revisionOf(source, id, selector)
        val cacheControl = cacheControlOf(readable, selector)
        return source.read(id, revision, path, properties.maxFileSize.toBytes()) { item, stream ->
            use(ItemRead(readable.configSet, revision, item, cacheControl, stream))
        }
    }

    private fun cacheControlOf(readable: ReadableConfigSet, selector: RevisionSelector): String =
        cacheControl(selector, readable.public, properties.latestMaxAge)

    private fun sourceOf(configSet: ConfigSet): ConfigSource =
        sources[configSet.sourceType] ?: error("No ConfigSource adapter for ${configSet.sourceType}")

    private fun revisionOf(source: ConfigSource, id: ConfigSetId, selector: RevisionSelector): RevisionId =
        when (selector) {
            RevisionSelector.Latest -> source.latest(id) ?: throw ContentException.NotPublishedYet(id)
            is RevisionSelector.Exact -> selector.id
        }

    companion object {
        /** The revision list has no pagination in v1, so this limit keeps each response small (design 12.1). */
        const val MAX_REVISIONS = 100
    }
}
