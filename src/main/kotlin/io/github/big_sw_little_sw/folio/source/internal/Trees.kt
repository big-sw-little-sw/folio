package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.source.SourcePath
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

// Lookups in a cached repository's commits and trees, for the source module's reads.

fun commitOf(
    repository: Repository,
    id: ObjectId,
): RevCommit = RevWalk(repository).use { it.parseCommit(id) }

/** The tree at [path] in [commit], or null if [path] is missing or not a directory. */
fun subtreeOf(
    repository: Repository,
    commit: RevCommit,
    path: SourcePath,
): ObjectId? {
    if (path.isRoot) return commit.tree
    return TreeWalk.forPath(repository, path.value, commit.tree)?.use { if (it.isSubtree) it.getObjectId(0) else null }
}

/** The blob of the regular file at [path] in [commit], or null if [path] is missing or not a regular file. */
fun regularFileOf(
    repository: Repository,
    commit: RevCommit,
    path: SourcePath,
): ObjectId? {
    // The repository root is a directory, never a file.
    if (path.isRoot) return null
    return TreeWalk
        .forPath(repository, path.value, commit.tree)
        ?.use { if (isRegularFile(it.getFileMode(0))) it.getObjectId(0) else null }
}

/** Symlinks, submodules and directories are not files (ADR 0024). */
fun isRegularFile(mode: FileMode) = mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE
