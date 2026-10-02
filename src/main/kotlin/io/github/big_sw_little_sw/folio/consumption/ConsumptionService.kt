package io.github.big_sw_little_sw.folio.consumption

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetPath
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.consumption.internal.ConsumptionProperties
import io.github.big_sw_little_sw.folio.consumption.internal.OnDemandFetches
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.source.RevisionNotFoundException
import io.github.big_sw_little_sw.folio.source.RevisionRef
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceFile
import io.github.big_sw_little_sw.folio.source.SourcePath
import io.github.big_sw_little_sw.folio.sync.SyncedRevision
import io.github.big_sw_little_sw.folio.sync.SyncedRevisions
import org.springframework.stereotype.Service

/** What a consumer sees of a ConfigSet. [latestRevision] is null until the first sync. */
data class ConfigSetMetadata(
    val id: ConfigSetId,
    val path: ConfigSetPath,
    val latestRevision: String?,
)

/** The regular files beneath the ConfigSet's root path at [revision] (design 8.5). */
data class ConfigItemListing(
    val revision: String,
    val files: List<SourceFile>,
)

/** A file read at [revision]: its content, or that the caller's copy is current. */
sealed interface ConfigFileRead {
    val revision: String

    /** The raw bytes, exactly as stored; [format] is null for formats Folio does not know. */
    class Content(
        override val revision: String,
        val format: ConfigFormat?,
        val bytes: ByteArray,
        val validation: ValidationStatus,
    ) : ConfigFileRead

    /** `If-None-Match` named the file's entity tag; nothing was loaded. */
    data class NotModified(
        override val revision: String,
    ) : ConfigFileRead
}

/**
 * The consumption API (design 16): metadata, file listings and raw reads at `latest` or an exact synced revision, and
 * the synced revisions. Each read requires its action on the ConfigSet (ADR 0035); lookups come first, so a missing
 * ConfigSet gives not found (ADR 0010).
 *
 * `latest` is the last synced revision in the database, never this instance's branch tip, and exact reads accept only
 * revisions Folio synced. A revision missing from this instance's cache is fetched on demand, within bounds
 * (ADR 0032, ADR 0036).
 *
 * Deliberately not transactional: an on-demand fetch talks to the Git service, which must not hold a database
 * connection. Each call to another module's service runs in its own transaction.
 */
@Service
class ConsumptionService(
    private val configSets: ConfigSetService,
    private val revisions: SyncedRevisions,
    private val sources: SourceAccess,
    private val fetches: OnDemandFetches,
    private val properties: ConsumptionProperties,
) {
    /** Requires view on the ConfigSet. Cached like `latest`, because it names the latest revision. */
    fun metadata(id: ConfigSetId): Cacheable<ConfigSetMetadata> {
        val configSet = configSets.requireAllowed(id, Action.CONFIG_SET_VIEW)
        val metadata = ConfigSetMetadata(id, configSets.pathOf(configSet), revisions.latest(id))
        return Cacheable(
            metadata,
            digestTag(metadata.path.toString(), metadata.latestRevision.orEmpty()),
            cachePolicy(configSet, Action.CONFIG_SET_VIEW, RevisionSelector.Latest),
        )
    }

    /** Requires item read on the ConfigSet. The commit ID is the entity tag: the source never changes (ADR 0022). */
    fun list(
        id: ConfigSetId,
        selector: RevisionSelector,
    ): Cacheable<ConfigItemListing> {
        val configSet = configSets.requireAllowed(id, Action.CONFIG_ITEM_READ)
        val commitId = commitOf(id, selector)
        val files = reading(configSet, commitId) { sources.list(id.value, configSet.source, it) }
        return Cacheable(
            ConfigItemListing(commitId, files),
            commitId,
            cachePolicy(configSet, Action.CONFIG_ITEM_READ, selector),
        )
    }

    /**
     * Requires item read on the ConfigSet. The blob ID is the entity tag: it is content-addressed. When [ifNoneMatch]
     * names it, the file is neither loaded nor validated. A file over `folio.consumption.max-file-size` is refused.
     */
    fun read(
        id: ConfigSetId,
        path: SourcePath,
        selector: RevisionSelector,
        ifNoneMatch: String?,
    ): Cacheable<ConfigFileRead> {
        val configSet = configSets.requireAllowed(id, Action.CONFIG_ITEM_READ)
        val commitId = commitOf(id, selector)
        val cache = cachePolicy(configSet, Action.CONFIG_ITEM_READ, selector)
        val file = reading(configSet, commitId) { sources.find(id.value, configSet.source, path, it) }
        if (matchesIfNoneMatch(ifNoneMatch, file.blobId)) {
            return Cacheable(ConfigFileRead.NotModified(commitId), file.blobId, cache)
        }
        val bytes =
            reading(configSet, commitId) { sources.read(id.value, configSet.source, path, it, properties.maxFileBytes) }
        val format = ConfigFormat.of(path.value)
        val validation = format?.validate(bytes) ?: ValidationStatus.UNKNOWN
        return Cacheable(ConfigFileRead.Content(commitId, format, bytes, validation), file.blobId, cache)
    }

    /**
     * The newest synced revisions, at most [MAX_REVISIONS]; the first is `latest`. Requires version list on the
     * ConfigSet. Every change to the list moves its first entry or that entry's time, so they make the entity tag.
     */
    fun revisions(id: ConfigSetId): Cacheable<List<SyncedRevision>> {
        val configSet = configSets.requireAllowed(id, Action.CONFIG_VERSION_LIST)
        val newest = revisions.newest(id, MAX_REVISIONS)
        val first = newest.firstOrNull()
        return Cacheable(
            newest,
            digestTag(first?.commitId.orEmpty(), first?.syncedAt?.toString().orEmpty()),
            cachePolicy(configSet, Action.CONFIG_VERSION_LIST, RevisionSelector.Latest),
        )
    }

    private fun commitOf(
        id: ConfigSetId,
        selector: RevisionSelector,
    ): String =
        when (selector) {
            RevisionSelector.Latest -> {
                revisions.latest(id) ?: throw NotYetSyncedException(id)
            }

            is RevisionSelector.Exact -> {
                selector.commitId.takeIf { revisions.contains(id, it) }
                    ?: throw UnknownRevisionException(selector.commitId)
            }
        }

    /**
     * Runs [read] at [commitId]. Caches are per instance, so this one may lack a revision that another instance
     * synced; it then fetches on demand and reads again.
     */
    private fun <T> reading(
        configSet: ConfigSet,
        commitId: String,
        read: (RevisionRef) -> T,
    ): T {
        val revision = RevisionRef.Commit(commitId)
        return try {
            read(revision)
        } catch (_: RevisionNotFoundException) {
            if (!fetches.fetch(configSet, revision)) throw RevisionNotAvailableException(commitId)
            try {
                read(revision)
            } catch (_: RevisionNotFoundException) {
                throw RevisionNotAvailableException(commitId)
            }
        }
    }

    private fun cachePolicy(
        configSet: ConfigSet,
        action: Action,
        selector: RevisionSelector,
    ) = CachePolicy.of(selector, configSets.isPublic(configSet, action), properties.latestMaxAge)

    companion object {
        /** The revision listing is not paginated in v1; older synced revisions stay readable by commit ID. */
        const val MAX_REVISIONS = 100
    }
}
