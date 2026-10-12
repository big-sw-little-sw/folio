package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.SourceType
import io.github.big_sw_little_sw.folio.content.ConfigItem
import io.github.big_sw_little_sw.folio.content.ConfigItemInfo
import io.github.big_sw_little_sw.folio.content.ConfigSource
import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.content.Revision
import io.github.big_sw_little_sw.folio.content.RevisionId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * The Git adapter of [ConfigSource]. `git_revision` gives the published revisions, and the cache of this instance gives
 * their content. A revision ID is a commit ID, and the ETag of a ConfigItem is its Git blob ID. A read accepts only a
 * published revision. If the cache of this instance does not have the commit, the read fetches the commit on demand.
 */
@Component
class GitConfigSource(
    private val gitSourceRepository: GitSourceRepository,
    private val cache: RepositoryCache,
    private val onDemandFetcher: OnDemandFetcher,
) : ConfigSource {
    override val type = SourceType.GIT

    override fun latest(configSet: ConfigSetId): RevisionId? = gitSourceRepository.latest(configSet)?.id

    override fun revisions(configSet: ConfigSetId, limit: Int): List<Revision> =
        gitSourceRepository.revisions(configSet, limit)

    /**
     * The regular files below the root path, in path order, without the files whose names are not item paths. Sync
     * logs the number of these files one time when it publishes the revision, so the list does not log it. A list
     * above the limit of tree entries fails with `TOO_MANY_FILES`.
     */
    override fun list(configSet: ConfigSetId, revision: RevisionId): List<ConfigItemInfo> =
        reading(configSet, revision) { repository, commit, source ->
            try {
                listFiles(repository, commit, source.rootPath)?.files.orEmpty()
            } catch (failure: GitFailure) {
                throw failure.toContentException()
            }
        }

    override fun <T> read(
        configSet: ConfigSetId,
        revision: RevisionId,
        path: ItemPath,
        maxBytes: Long,
        consume: (ConfigItem, InputStream) -> T,
    ): T = reading(configSet, revision) { repository, commit, source ->
        val blob =
            regularFileOf(repository, commit, source.rootPath.resolve(path))
                ?: throw ContentException.ItemNotFound()
        val size = repository.newObjectReader().use { it.getObjectSize(blob, BLOB) }
        if (size > maxBytes) throw ContentException.ItemTooLarge(maxBytes)
        BlobStream(repository, blob).use { consume(ConfigItem(path, size, blob.name), it) }
    }

    /**
     * Runs [read] with the commit of a published revision. The repository stays open until [read] returns. If the
     * cache of this instance does not have the commit, the read fetches it on demand, with the limits of
     * [OnDemandFetcher], and then reads again.
     */
    private fun <T> reading(
        configSet: ConfigSetId,
        revision: RevisionId,
        read: (Repository, RevCommit, GitSourceDefinition) -> T,
    ): T {
        val source =
            gitSourceRepository.findPublished(configSet, revision) ?: throw ContentException.RevisionNotFound(revision)
        // A published revision is always a commit ID, so fromString does not fail.
        val commit = ObjectId.fromString(revision.value)
        readCached(configSet, commit, source, read)?.let { return it.value }
        onDemandFetcher.fetch(configSet, source, revision, commit)
        // A sweep or a size check can delete the repository after the fetch. Then the read fails with
        // RevisionUnavailable.
        return readCached(configSet, commit, source, read)?.value
            ?: throw ContentException.RevisionUnavailable(revision)
    }

    /** Returns the result of [read], or null if the cache does not have [commit]. */
    private fun <T> readCached(
        configSet: ConfigSetId,
        commit: ObjectId,
        source: GitSourceDefinition,
        read: (Repository, RevCommit, GitSourceDefinition) -> T,
    ): Cached<T>? = cache.reading(configSet, { null }) { repository ->
        commitOrNull(repository, commit)?.let { Cached(read(repository, it, source)) }
    }

    /**
     * Holds the result of [read], so [read] can return null. A null from [readCached] then means only that the cache
     * does not have the commit.
     */
    private class Cached<T>(val value: T)

    /** Opens the blob at the first read, as [ConfigSource.read] requires. */
    private class BlobStream(private val repository: Repository, private val blob: ObjectId) : InputStream() {
        private var opened: InputStream? = null

        private fun stream(): InputStream = opened ?: repository.open(blob, BLOB).openStream().also { opened = it }

        override fun read(): Int = stream().read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream().read(buffer, offset, length)

        override fun close() {
            opened?.close()
        }
    }

    private companion object {
        const val BLOB = Constants.OBJ_BLOB
    }
}
