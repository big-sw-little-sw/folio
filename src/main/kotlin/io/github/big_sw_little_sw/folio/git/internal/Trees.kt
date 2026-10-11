package io.github.big_sw_little_sw.folio.git.internal

import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
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
