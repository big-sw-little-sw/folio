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
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * The Git adapter of the Source interface (design 9). `git_revision` gives the published revisions, and the cache of
 * this instance gives their content. A revision is a commit ID. The ETag of a ConfigItem is its Git blob ID.
 *
 * A read accepts only a published revision. If the cache of this instance does not have the commit, the read fails
 * with `RevisionUnavailable`. Slice 6 adds the fetch on demand with its limits, together with sync (design 10.8, 20).
 */
@Component
class GitConfigSource(private val sources: GitSourceRepository, private val cache: RepositoryCache) : ConfigSource {
    override val type = SourceType.GIT

    override fun latest(configSet: ConfigSetId): RevisionId? = sources.latest(configSet)?.id

    override fun revisions(configSet: ConfigSetId, limit: Int): List<Revision> = sources.revisions(configSet, limit)

    /**
     * The regular files below the root path, in path order. Git permits file names that are not item paths, for
     * example a name with a backslash. Consumers cannot read such a file, so the list leaves it out, and the log gives
     * the number of these files.
     *
     * Trees can share subtrees, so a small commit can name billions of files. The walk counts each entry that it
     * visits, trees too, and fails with `TOO_MANY_FILES` above [MAX_LIST_ENTRIES].
     */
    override fun list(configSet: ConfigSetId, revision: RevisionId): List<ConfigItemInfo> =
        reading(configSet, revision) { repository, commit, source ->
            val root = rootTreeOf(repository, commit, source.rootPath) ?: return@reading emptyList()
            val files = mutableListOf<ConfigItemInfo>()
            var skipped = 0
            TreeWalk(repository).use { walk ->
                walk.addTree(root)
                // Without the recursive mode, the walk gives each tree, so the count includes the trees.
                walk.isRecursive = false
                var visited = 0
                while (walk.next()) {
                    if (++visited > MAX_LIST_ENTRIES) throw tooManyFiles()
                    when {
                        walk.isSubtree -> walk.enterSubtree()
                        !isRegularFile(walk.getFileMode(0)) -> Unit
                        !ItemPath.isValid(walk.pathString) -> skipped++
                        else -> files += ConfigItemInfo(ItemPath(walk.pathString), sizeOf(walk))
                    }
                }
            }
            // INFO, not WARN: any reader of a public ConfigSet can repeat the list, and the revision never changes.
            if (skipped > 0) log.info("ConfigSet {} has {} files that are not item paths", configSet.toApiId(), skipped)
            files.sortedBy { it.path.value }
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

    /** Runs [read] with the commit of a published revision. The repository stays open until [read] returns. */
    private fun <T> reading(
        configSet: ConfigSetId,
        revision: RevisionId,
        read: (Repository, RevCommit, GitSourceDefinition) -> T,
    ): T {
        val source = sources.findPublished(configSet, revision) ?: throw ContentException.RevisionNotFound(revision)
        // A published revision is always a commit ID, so fromString does not fail.
        val id = ObjectId.fromString(revision.value)
        return cache.reading(configSet, { throw ContentException.RevisionUnavailable(revision) }) { repository ->
            read(repository, cachedCommit(repository, id, revision), source)
        }
    }

    /**
     * A published commit can be missing from the cache of this instance, for example after a delete of the cache. Then
     * the read fails with `RevisionUnavailable`.
     */
    private fun cachedCommit(repository: Repository, id: ObjectId, revision: RevisionId): RevCommit =
        commitOrNull(repository, id) ?: throw ContentException.RevisionUnavailable(revision)

    /**
     * Opens the blob at the first read. Thus a caller that reads nothing, for example for a 304 response, loads no
     * content (design 9).
     */
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
        val log = LoggerFactory.getLogger(GitConfigSource::class.java)
        const val BLOB = Constants.OBJ_BLOB

        /**
         * The maximum number of tree entries, files and trees, that a file list visits. Configuration repositories
         * have far fewer files. The limit stops a commit whose shared subtrees name billions of files.
         */
        const val MAX_LIST_ENTRIES = 10_000

        fun sizeOf(walk: TreeWalk): Long = walk.objectReader.getObjectSize(walk.getObjectId(0), BLOB)

        fun tooManyFiles() = ContentException.SourceFailed(
            "TOO_MANY_FILES",
            "The revision has more than $MAX_LIST_ENTRIES files and directories below the root path.",
        )
    }
}
