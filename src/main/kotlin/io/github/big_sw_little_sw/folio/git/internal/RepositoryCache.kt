package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.BaseUnits
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_FETCH_SECTION
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_GC_SECTION
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_AUTO
import org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_AUTOGC
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * The cache of this Folio instance: one bare repository for each ConfigSet in `folio.git.cache-directory`, named by the
 * ID of the ConfigSet. Only the owner can access the directory (`0700`), because the repositories contain the
 * configuration. The database is the source of truth for published revisions, so Folio can delete the cache at any
 * time. A missing or damaged repository costs one full fetch.
 *
 * Reads run during a fetch, because a fetch only adds objects and moves a ref. A delete or a new start of a repository
 * waits for the reads, but only for the time that its caller gives. Thus a slow download cannot hold a fetch past its
 * deadline. Before a fetch, Folio examines only the commit and the tree of the tip. A missing object deeper in the
 * history stays an error until an operator deletes the repository.
 *
 * The bean sets the global JGit settings before any repository opens. [IsolatedSsh] depends on this bean, so the
 * settings also apply to each `ls-remote`.
 *
 * The cache gauges show the measurement of the last [sweep], because a walk of the cache at each scrape could be slow.
 * Both gauges are 0 until the first sweep.
 */
@Component
class RepositoryCache(properties: GitProperties, meters: MeterRegistry) {
    private val root: Path = createOwnerOnly(properties.cacheDirectory)
    private val locks = ConcurrentHashMap<ConfigSetId, Locks>()
    private val measuredSize = AtomicLong()
    private val measuredRepositories = AtomicLong()

    /**
     * The empty SSH home directory of the connections, in the cache directory. JGit resolves `~` and `~/.ssh` against
     * it, not against the home of the process user. Folio never writes to it. Its name is not a UUID, so no repository
     * uses it.
     */
    val sshHome: Path = Files.createDirectories(root.resolve(SSH_HOME))

    init {
        IsolatedSystemReader.install()
        streamLargeBlobs()
        Gauge
            .builder("folio.git.cache.size", measuredSize) { it.get().toDouble() }
            .baseUnit(BaseUnits.BYTES)
            .register(meters)
        Gauge.builder("folio.git.cache.repositories", measuredRepositories) { it.get().toDouble() }.register(meters)
    }

    private fun directory(id: ConfigSetId): Path = root.resolve(id.value.toString())

    /**
     * Runs [block] with the fetch lock of the ConfigSet, so fetches of one ConfigSet run one at a time in this process.
     * Returns null, and does not run [block], if another fetch holds the lock for longer than [wait]. The lock is
     * reentrant, so a thread that holds it already gets it again at once and does not wait.
     */
    fun <T : Any> withFetchLock(id: ConfigSetId, wait: Duration, block: () -> T): T? =
        withLock(locksOf(id).fetch, wait, block)

    /**
     * Runs [fetch] on the repository of the ConfigSet. First, the method replaces a missing or damaged repository with
     * a new, empty repository. This new start waits a maximum of [wait] for the reads of the repository, and then fails
     * with `DEADLINE_EXCEEDED`. The caller must hold the fetch lock ([withFetchLock]).
     */
    fun <T> fetching(id: ConfigSetId, branch: Branch, wait: Duration, fetch: (Repository) -> T): T {
        check(locksOf(id).fetch.isHeldByCurrentThread) { "A fetch needs the fetch lock of the ConfigSet." }
        val repository =
            openReadable(id, branch) ?: recreate(id, wait) ?: throw GitFailure(FailureCode.DEADLINE_EXCEEDED)
        return repository.use {
            disableAutoGc(it)
            fetch(it)
        }
    }

    /**
     * Returns the total size in bytes of the repository files on disk. The walk does not follow symbolic links. It
     * skips files that disappear during the walk, for example the lock files of JGit.
     */
    fun size(id: ConfigSetId): Long {
        if (!directory(id).exists()) return 0
        var total = 0L
        Files.walkFileTree(
            directory(id),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile) total += attributes.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                    if (error is NoSuchFileException) FileVisitResult.CONTINUE else throw error
            },
        )
        return total
    }

    /** Runs [read] on the repository of the ConfigSet, or runs [missing] if the cache has no repository for it. */
    fun <T> reading(id: ConfigSetId, missing: () -> T, read: (Repository) -> T): T = locksOf(id).cache.read {
        val repository = openExisting(id) ?: return@read missing()
        repository.use(read)
    }

    /**
     * Deletes the repositories of the ConfigSets that no longer exist. [existing] returns the ConfigSets
     * of its argument that exist. The sweep lists the directory before it calls [existing]. Thus a ConfigSet that the
     * listing finds and that exists is always in the answer, and the sweep never deletes its repository.
     *
     * The sweep never follows symbolic links. It examines only directories whose name is the UUID of a ConfigSet, so
     * it never deletes [sshHome] or another entry.
     *
     * The sweep does not wait. It skips a repository that a fetch or a read holds, and a later sweep deletes it. A
     * deleted ConfigSet gets no new reads or syncs, so the repository becomes free. The sweep runs on the scheduler
     * thread of the polls ([SyncSchedule]). Without the skip, a slow read of a deleted ConfigSet would stop the polls
     * for up to the fetch deadline.
     *
     * After the deletes, the sweep measures the repositories that stay, also a repository that it could not delete.
     */
    fun sweep(existing: (Set<ConfigSetId>) -> Set<ConfigSetId>) {
        val cached =
            Files.list(root).use { entries ->
                entries
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .toList()
                    .mapNotNull { configSetOf(it.fileName.toString()) }
                    .toSet()
            }
        val gone = if (cached.isEmpty()) emptySet() else cached - existing(cached)
        val deleted =
            gone.filterTo(mutableSetOf()) { id ->
                // The fetch lock comes first, as in a fetch, so a delete never removes the files of a running fetch.
                val removed = withFetchLock(id, Duration.ZERO) { delete(id, Duration.ZERO) } == true
                if (removed) log.info("Deleted the cached repository of the deleted ConfigSet {}", id.toApiId())
                removed
            }
        val remaining = cached - deleted
        measuredSize.set(remaining.sumOf(::size))
        measuredRepositories.set(remaining.size.toLong())
    }

    /**
     * Deletes the repository of the ConfigSet, and does not follow symbolic links. The delete waits a maximum of [wait]
     * for the reads of the repository. Returns false if the reads did not end in that time. The repository then stays
     * until a later delete.
     */
    fun delete(id: ConfigSetId, wait: Duration): Boolean =
        withLock(locksOf(id).cache.writeLock(), wait) { deleteEntry(id) } != null

    @OptIn(ExperimentalPathApi::class)
    private fun deleteEntry(id: ConfigSetId) {
        val entry = directory(id)
        if (entry.exists(LinkOption.NOFOLLOW_LINKS)) entry.deleteRecursively()
    }

    /** Returns the new, empty repository, or null if the reads of the old repository did not end in [wait]. */
    private fun recreate(id: ConfigSetId, wait: Duration): Repository? = withLock(locksOf(id).cache.writeLock(), wait) {
        deleteEntry(id)
        val repository = open(id)
        try {
            repository.create(true)
        } catch (e: IOException) {
            repository.close()
            throw e
        }
        repository
    }

    /**
     * Returns the repository if it exists, opens, and has the commit and the tree that [branch] points to. Otherwise
     * returns null.
     */
    private fun openReadable(id: ConfigSetId, branch: Branch): Repository? {
        val repository = openExisting(id) ?: return null
        if (isReadable(repository, branch)) return repository
        repository.close()
        return null
    }

    private fun isReadable(repository: Repository, branch: Branch): Boolean = try {
        val tip = repository.exactRef(branch.ref)
        repository.objectDatabase.exists() &&
            (tip == null || repository.objectDatabase.has(commitOf(repository, tip.objectId).tree))
    } catch (_: IOException) {
        false
    }

    private fun openExisting(id: ConfigSetId): Repository? {
        if (!directory(id).exists()) return null
        return try {
            open(id, mustExist = true)
        } catch (_: IOException) {
            null
        }
    }

    private fun open(id: ConfigSetId, mustExist: Boolean = false): Repository = FileRepositoryBuilder()
        .setGitDir(directory(id).toFile())
        .setBare()
        .setMustExist(mustExist)
        .build()

    private fun locksOf(id: ConfigSetId) = locks.computeIfAbsent(id) { Locks() }

    /**
     * Runs [block] with [lock], or returns null if the lock was not free in [wait]. A writer that waits for the cache
     * lock blocks new reads. Thus the limit on the wait also limits how long the new reads wait.
     */
    private fun <T : Any> withLock(lock: Lock, wait: Duration, block: () -> T): T? {
        if (!lock.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * [fetch] makes the fetches of one ConfigSet run one at a time. [cache] lets reads share a repository, and makes a
     * delete or a new start wait for the reads.
     */
    private class Locks {
        val fetch = ReentrantLock()
        val cache = ReentrantReadWriteLock()
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(RepositoryCache::class.java)

        const val SSH_HOME = "ssh-home"

        /** Returns the ConfigSet whose repository has the directory [name], or null for another name. */
        fun configSetOf(name: String): ConfigSetId? = try {
            UUID.fromString(name).takeIf { it.toString() == name }?.let(::ConfigSetId)
        } catch (_: IllegalArgumentException) {
            null
        }

        /**
         * JGit streams a blob from the pack only above this size. A smaller blob is a byte array in memory. The value
         * is the size limit of validation in the `content` module, which keeps only such files in memory. Thus a
         * larger file never fills the heap.
         */
        const val STREAM_THRESHOLD_BYTES = 3 * 1024 * 1024

        /**
         * Sets the stream threshold of JGit for the process. The default of JGit is 50 MiB. Without this setting, each
         * read of a file below that size holds the full file in memory. Folio is the only user of JGit in the process,
         * so the setting affects no other code.
         */
        fun streamLargeBlobs() {
            WindowCacheConfig().apply { streamFileThreshold = STREAM_THRESHOLD_BYTES }.install()
        }
    }
}

/**
 * Creates [directory] with access for the owner only (`0700`). Without POSIX permissions, for example on Windows, the
 * directory gets the defaults of the file system.
 */
private fun createOwnerOnly(directory: Path): Path {
    if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return Files.createDirectories(directory)
    val ownerOnly = PosixFilePermissions.fromString("rwx------")
    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(ownerOnly))
    Files.setPosixFilePermissions(directory, ownerOnly)
    return directory
}

/**
 * JGit runs an automatic gc after a fetch, by default on a background thread. In the cache, the gc would race with
 * reads, with the size check and with a delete. The setting goes into the config of the repository, because the
 * [IsolatedSystemReader] ignores the config files of the host. Each fetch writes the setting only if it is missing.
 */
private fun disableAutoGc(repository: Repository) {
    val config = repository.config
    val fetchAutoGc = config.getBoolean(CONFIG_FETCH_SECTION, CONFIG_KEY_AUTOGC, true)
    if (!fetchAutoGc && config.getInt(CONFIG_GC_SECTION, CONFIG_KEY_AUTO, -1) == 0) return
    config.setBoolean(CONFIG_FETCH_SECTION, null, CONFIG_KEY_AUTOGC, false)
    config.setInt(CONFIG_GC_SECTION, null, CONFIG_KEY_AUTO, 0)
    config.save()
}
