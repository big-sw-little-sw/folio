package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.content.ConfigItemInfo
import io.github.big_sw_little_sw.folio.content.ItemPath
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

fun commitOf(repository: Repository, id: ObjectId): RevCommit = RevWalk(repository).use { it.parseCommit(id) }

/** Returns the commit [id], or null if the repository does not have [id] or [id] is not a commit. */
fun commitOrNull(repository: Repository, id: ObjectId): RevCommit? = try {
    commitOf(repository, id)
} catch (_: MissingObjectException) {
    null
} catch (_: IncorrectObjectTypeException) {
    null
}

/** Returns the tree of the root path in [commit], or null if the root path is missing or is not a directory. */
fun rootTreeOf(repository: Repository, commit: RevCommit, rootPath: RootPath): ObjectId? {
    if (rootPath.isRepositoryRoot) return commit.tree
    return TreeWalk.forPath(repository, rootPath.value, commit.tree)?.use {
        if (it.isSubtree) it.getObjectId(0) else null
    }
}

/** Returns the blob of the regular file at [path] in [commit], or null if [path] is missing or not a regular file. */
fun regularFileOf(repository: Repository, commit: RevCommit, path: String): ObjectId? =
    TreeWalk.forPath(repository, path, commit.tree)?.use {
        if (isRegularFile(it.getFileMode(0))) it.getObjectId(0) else null
    }

/** Symbolic links, submodules and directories are not ConfigItems. */
fun isRegularFile(mode: FileMode) = mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE

/** The ConfigItems of a revision, in path order, and the number of file names that are not item paths. */
class FileListing(val files: List<ConfigItemInfo>, val skipped: Int)

/**
 * The maximum number of tree entries, files and trees, that a file list visits. Trees can share subtrees, so a small
 * commit can name billions of files. Without the limit, such a commit makes Folio build a huge list in memory.
 * Configuration repositories have far fewer files.
 */
const val MAX_LIST_ENTRIES = 10_000

/**
 * Lists the regular files below the root path of [commit], or returns null if the root path is missing or is not a
 * directory. Git permits file names that are not item paths, for example a name with a backslash. Consumers cannot
 * read such a file, so the list leaves it out and counts it in [FileListing.skipped]. Above [MAX_LIST_ENTRIES], the
 * list throws [GitFailure] with `TOO_MANY_FILES`.
 */
fun listFiles(repository: Repository, commit: RevCommit, rootPath: RootPath): FileListing? {
    val root = rootTreeOf(repository, commit, rootPath) ?: return null
    val files = mutableListOf<ConfigItemInfo>()
    var skipped = 0
    TreeWalk(repository).use { walk ->
        walk.addTree(root)
        // Without the recursive mode, the walk gives each tree, so the count includes the trees.
        walk.isRecursive = false
        var visited = 0
        while (walk.next()) {
            if (++visited > MAX_LIST_ENTRIES) throw GitFailure(FailureCode.TOO_MANY_FILES)
            when {
                walk.isSubtree -> walk.enterSubtree()
                !isRegularFile(walk.getFileMode(0)) -> Unit
                !ItemPath.isValid(walk.pathString) -> skipped++
                else -> files += ConfigItemInfo(ItemPath(walk.pathString), sizeOf(walk))
            }
        }
    }
    return FileListing(files.sortedBy { it.path.value }, skipped)
}

private fun sizeOf(walk: TreeWalk): Long = walk.objectReader.getObjectSize(walk.getObjectId(0), Constants.OBJ_BLOB)
