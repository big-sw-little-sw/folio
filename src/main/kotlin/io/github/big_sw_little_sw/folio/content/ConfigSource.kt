package io.github.big_sw_little_sw.folio.content

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.SourceType
import java.io.InputStream
import java.time.Instant

/**
 * The origin of the content of a ConfigSet (design 9). There is one adapter for each [SourceType]. Spring gives the
 * adapter beans to the `content` module, so `content` does not depend on the modules of the adapters.
 *
 * A revision is a snapshot of the full ConfigSet. An adapter accepts only revisions that it published, and a published
 * revision never changes. Adapters do not authorize, because the `content` module authorizes each read before it calls
 * an adapter.
 *
 * Each method throws a [ContentException] for an expected failure, for example [ContentException.RevisionNotFound]
 * for a revision that the ConfigSet did not publish.
 */
interface ConfigSource {
    val type: SourceType

    /** Returns the newest published revision, or null if the ConfigSet has no published revision. */
    fun latest(configSet: ConfigSetId): RevisionId?

    /** Returns the published revisions, newest first, with a maximum of [limit]. */
    fun revisions(configSet: ConfigSetId, limit: Int): List<Revision>

    /** Returns the ConfigItems at a published revision. */
    fun list(configSet: ConfigSetId, revision: RevisionId): List<ConfigItemInfo>

    /**
     * Opens one ConfigItem, calls [consume] with its content as a stream, and closes the stream after [consume]
     * returns. The adapter owns the stream, so a caller cannot leak a repository handle or a database connection.
     *
     * The adapter must get the size and the ETag of the [ConfigItem] without a read of the content. Thus the adapter
     * can throw [ContentException.ItemTooLarge] above [maxBytes] before it opens the content. The adapter reads the
     * content only when [consume] reads the stream. Thus a 304 response reads no content (design 12.1).
     */
    fun <T> read(
        configSet: ConfigSetId,
        revision: RevisionId,
        path: ItemPath,
        maxBytes: Long,
        consume: (ConfigItem, InputStream) -> T,
    ): T
}

data class Revision(val id: RevisionId, val publishedAt: Instant)

/** [size] is in bytes. */
data class ConfigItemInfo(val path: ItemPath, val size: Long)

/**
 * [etag] is a hash of the content, for example the Git blob ID. The same content always has the same [etag]. The
 * [etag] has no quotes, because the `content` module adds the quotes in the HTTP header.
 */
data class ConfigItem(val path: ItemPath, val size: Long, val etag: String)
