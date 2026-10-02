package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.source.Branch
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import kotlin.io.path.exists

/**
 * One bare repository per ConfigSet under `folio.git.cache-directory`, named by the ConfigSet ID (ADR 0024). The
 * cache is disposable: a missing or unreadable repository is deleted and created empty, and the next fetch fills it.
 *
 * Fetches of one ConfigSet run one at a time in this process; sync leases add that across instances. Reads run
 * alongside fetches, because a fetch only adds objects and moves a ref. Recreating a repository waits for reads.
 * Only the tip's commit and tree are checked before a fetch; a deeper missing object surfaces as an error until an
 * operator deletes the repository.
 */
@Component
class RepositoryCache(
    properties: SourceProperties,
) {
    // Owner-only where the file system supports POSIX permissions: the cache holds the configuration content.
    private val root: Path = createOwnerOnly(properties.cacheDirectory)
    private val locks = ConcurrentHashMap<UUID, Locks>()

    private fun directory(id: UUID): Path = root.resolve("$id$SUFFIX")

    /** Runs [fetch] on the cached repository of [id], recreating it first if it is missing or unreadable. */
    fun <T> fetching(
        id: UUID,
        branch: Branch,
        fetch: (Repository) -> T,
    ): T =
        locksOf(id).fetch.withLock {
            val repository = openReadable(id, branch) ?: recreate(id)
            repository.use(fetch)
        }

    /** Runs [read] on the cached repository of [id], or returns null if there is none. */
    fun <T> reading(
        id: UUID,
        read: (Repository) -> T,
    ): T? =
        locksOf(id).cache.read {
            openExisting(id)?.use(read)
        }

    /**
     * Deletes the repository of [id], waiting for reads but not for a fetch: a fetch running at the same time may fail
     * or leave a repository behind, which the next sweep removes (ADR 0034).
     */
    fun delete(id: UUID) {
        locksOf(id).cache.write { directory(id).toFile().deleteRecursively() }
    }

    /**
     * The IDs that have an entry in the cache directory. Only entries named exactly like [directory] count; anything
     * else there, such as the SSH home directory, is never reported and so never deleted.
     */
    fun ids(): Set<UUID> =
        Files.list(root).use { entries ->
            entries
                .map { it.fileName.toString() }
                .toList()
                .mapNotNull(::idOf)
                .toSet()
        }

    private fun idOf(name: String): UUID? {
        // UUID.fromString accepts non-canonical forms such as "1-1-1-1-1"; the round trip rejects them.
        val id = runCatching { UUID.fromString(name.removeSuffix(SUFFIX)) }.getOrNull() ?: return null
        return id.takeIf { directory(it).fileName.toString() == name }
    }

    private fun recreate(id: UUID): Repository =
        locksOf(id).cache.write {
            directory(id).toFile().deleteRecursively()
            val repository = open(id)
            try {
                repository.create(true)
            } catch (e: IOException) {
                repository.close()
                throw e
            }
            repository
        }

    /** The repository of [id] if it exists, opens and can read the commit [branch] points to, if any. */
    private fun openReadable(
        id: UUID,
        branch: Branch,
    ): Repository? {
        val repository = openExisting(id) ?: return null
        if (isReadable(repository, branch)) return repository
        repository.close()
        return null
    }

    private fun isReadable(
        repository: Repository,
        branch: Branch,
    ): Boolean =
        try {
            val tip = repository.exactRef(branch.ref)
            repository.objectDatabase.exists() && (tip == null || hasTree(repository, tip.objectId))
        } catch (_: IOException) {
            false
        }

    private fun hasTree(
        repository: Repository,
        commitId: ObjectId,
    ) = repository.objectDatabase.has(RevWalk(repository).use { it.parseCommit(commitId) }.tree)

    private fun openExisting(id: UUID): Repository? {
        if (!directory(id).exists()) return null
        return try {
            open(id, mustExist = true)
        } catch (_: IOException) {
            null
        }
    }

    private fun open(
        id: UUID,
        mustExist: Boolean = false,
    ): Repository =
        FileRepositoryBuilder()
            .setGitDir(directory(id).toFile())
            .setBare()
            .setMustExist(mustExist)
            .build()

    private fun locksOf(id: UUID) = locks.computeIfAbsent(id) { Locks() }

    /** [fetch] serializes fetches; [cache] lets reads share the repository and recreation exclude them. */
    private class Locks {
        val fetch = ReentrantLock()
        val cache = ReentrantReadWriteLock()
    }

    private companion object {
        const val SUFFIX = ".git"
    }
}

private fun createOwnerOnly(directory: Path): Path {
    if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return Files.createDirectories(directory)
    val ownerOnly = PosixFilePermissions.fromString("rwx------")
    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(ownerOnly))
    Files.setPosixFilePermissions(directory, ownerOnly)
    return directory
}
