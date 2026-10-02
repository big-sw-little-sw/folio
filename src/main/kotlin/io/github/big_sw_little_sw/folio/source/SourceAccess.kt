package io.github.big_sw_little_sw.folio.source

import io.github.big_sw_little_sw.folio.credential.CredentialDisabledException
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPair
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPairs
import io.github.big_sw_little_sw.folio.credential.CredentialNotFoundException
import io.github.big_sw_little_sw.folio.credential.GitInstanceNotConfiguredException
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.source.internal.FetchDeadline
import io.github.big_sw_little_sw.folio.source.internal.IsolatedSystemReader
import io.github.big_sw_little_sw.folio.source.internal.RepositoryCache
import io.github.big_sw_little_sw.folio.source.internal.SourceProperties
import io.github.big_sw_little_sw.folio.source.internal.SshConnections
import io.github.big_sw_little_sw.folio.source.internal.commitOf
import io.github.big_sw_little_sw.folio.source.internal.isRegularFile
import io.github.big_sw_little_sw.folio.source.internal.regularFileOf
import io.github.big_sw_little_sw.folio.source.internal.sshUrl
import io.github.big_sw_little_sw.folio.source.internal.subtreeOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.LargeObjectException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * Git access for ConfigSet sources (ADR 0024): the onboarding check, fetching the branch into the ConfigSet's
 * cached bare repository, and reads beneath the root path. Nothing here authorizes; callers do. Reads use only
 * the cache and never contact the Git service.
 *
 * Fetches of one ConfigSet run one at a time in this process, from their ls-remote on (ADR 0036).
 *
 * [configSetId] keys the cache. Logs carry at most a ConfigSet ID and a failure code (ADR 0027).
 */
@Service
class SourceAccess(
    private val keyPairs: CredentialKeyPairs,
    private val connections: SshConnections,
    private val cache: RepositoryCache,
    private val properties: SourceProperties,
) {
    init {
        IsolatedSystemReader.install()
    }

    /**
     * Checks that the credential reaches the repository, the branch exists and the root path is a directory at its
     * tip, fetching the branch into the cache. With [keyId], uses that pending key instead of the active one, to
     * verify it before activation.
     */
    fun check(
        configSetId: UUID,
        source: SourceDefinition,
        keyId: KeyId?,
    ): SourceCheck {
        val credential =
            if (keyId == null) keyPairs.active(source.credentialId) else keyPairs.pending(source.credentialId, keyId)
        val commitId =
            try {
                fetch(configSetId, source, credential)
            } catch (e: SourceAccessFailedException) {
                return SourceCheck.Failed(e.failure)
            }
        val rootFound =
            cache.reading(configSetId) {
                subtreeOf(it, commitOf(it, ObjectId.fromString(commitId)), source.rootPath) != null
            }
        if (rootFound == true) return SourceCheck.Passed(commitId)
        log.warn("Source check of ConfigSet {} failed: {}", configSetId, SourceFailure.ROOT_PATH_NOT_FOUND)
        return SourceCheck.Failed(SourceFailure.ROOT_PATH_NOT_FOUND)
    }

    /**
     * Fetches the source's branch into the cache with the credential's active key and returns the commit ID of its
     * tip. Throws [SourceAccessFailedException] if Git access fails, the fetch passes its deadline, or the credential
     * cannot be used: disabled, without an active key or on a Git instance no longer configured (ADR 0032).
     *
     * Call it outside a transaction: it talks to the Git service, and it handles the credential module's exceptions,
     * which would mark a surrounding transaction rollback-only (ADR 0006).
     */
    fun fetch(
        configSetId: UUID,
        source: SourceDefinition,
    ): String = fetch(configSetId, source, activeKey(configSetId, source))

    /**
     * Makes sure the cache holds [commit], fetching the branch with the active key only if it does not, and returns
     * whether the cache holds it afterwards. A caller that waited for another fetch of the ConfigSet usually finds the
     * commit and fetches nothing. Waits at most [wait] for that other fetch, then throws [SourceBusyException]. Fails
     * like [fetch] otherwise.
     */
    fun fetchIfMissing(
        configSetId: UUID,
        source: SourceDefinition,
        commit: RevisionRef.Commit,
        wait: Duration,
    ): Boolean {
        fun holds() =
            try {
                reading(configSetId, commit) { resolve(it, source, commit) }
                true
            } catch (_: RevisionNotFoundException) {
                false
            }
        return cache.withFetchLock(configSetId, wait) {
            if (!holds()) {
                val deadline = FetchDeadline(properties.fetchDeadline)
                fetchLocked(configSetId, source, activeKey(configSetId, source), deadline)
            }
            holds()
        } ?: throw SourceBusyException()
    }

    /** How long one fetch may take; a sync lease must outlast it (ADR 0032). */
    val fetchDeadline: Duration get() = properties.fetchDeadline

    /** The regular files beneath the root path at [revision], relative to it, in Git's tree order. */
    fun list(
        configSetId: UUID,
        source: SourceDefinition,
        revision: RevisionRef,
    ): List<SourceFile> =
        reading(configSetId, revision) { repository ->
            val commit = resolve(repository, source, revision)
            val root = subtreeOf(repository, commit, source.rootPath) ?: return@reading emptyList()
            TreeWalk(repository).use { walk ->
                walk.addTree(root)
                walk.isRecursive = true
                // Git allows names that are not valid paths here, such as ones with a backslash; they cannot be read.
                generateSequence { if (walk.next()) walk else null }
                    .filter { isRegularFile(it.getFileMode(0)) }
                    .mapNotNull { file ->
                        SourcePath.parseOrNull(file.pathString)?.let { path ->
                            val id = file.getObjectId(0)
                            SourceFile(path, id.name, walk.objectReader.getObjectSize(id, Constants.OBJ_BLOB))
                        }
                    }.toList()
            }
        }

    /** The regular file at [path] beneath the root path at [revision]: its blob ID and size, without its content. */
    fun find(
        configSetId: UUID,
        source: SourceDefinition,
        path: SourcePath,
        revision: RevisionRef,
    ): SourceFile =
        reading(configSetId, revision) { repository ->
            val blob = blobAt(repository, source, path, revision)
            SourceFile(path, blob.name, repository.newObjectReader().use { it.getObjectSize(blob, Constants.OBJ_BLOB) })
        }

    /**
     * The raw bytes of the regular file at [path] beneath the root path at [revision]. A file larger than [maxBytes]
     * is refused with [SourceFileTooLargeException] before it is loaded.
     */
    fun read(
        configSetId: UUID,
        source: SourceDefinition,
        path: SourcePath,
        revision: RevisionRef,
        maxBytes: Int,
    ): ByteArray =
        reading(configSetId, revision) { repository ->
            val blob = blobAt(repository, source, path, revision)
            val size = repository.newObjectReader().use { it.getObjectSize(blob, Constants.OBJ_BLOB) }
            if (size > maxBytes) throw SourceFileTooLargeException(path, maxBytes)
            try {
                repository.open(blob, Constants.OBJ_BLOB).getCachedBytes(maxBytes)
            } catch (_: LargeObjectException) {
                throw SourceFileTooLargeException(path, maxBytes)
            }
        }

    /** The deadline runs from the start, so it covers waiting for another fetch of the same ConfigSet. */
    private fun fetch(
        configSetId: UUID,
        source: SourceDefinition,
        credential: CredentialKeyPair,
    ): String {
        val deadline = FetchDeadline(properties.fetchDeadline)
        return cache.withFetchLock(configSetId, deadline.remaining()) {
            fetchLocked(configSetId, source, credential, deadline)
        } ?: throw failed(configSetId, SourceFailure.DEADLINE_EXCEEDED)
    }

    /**
     * Under the fetch lock: ls-remote first, so that a missing branch is reported as such and nothing is fetched on
     * failure. A repository that grew past the size limit is discarded; the next fetch starts it again from nothing.
     */
    private fun fetchLocked(
        configSetId: UUID,
        source: SourceDefinition,
        credential: CredentialKeyPair,
        deadline: FetchDeadline,
    ): String {
        val url = sshUrl(credential.gitInstance, source.repositoryPath)
        try {
            return connections.run(credential, deadline) { transport ->
                requireBranch(url, source.branch, transport)
                cache.fetching(configSetId, source.branch) {
                    val tip = fetchBranch(it, url, source.branch, transport, deadline)
                    if (cache.size(configSetId) > properties.maxRepositorySize.toBytes()) {
                        cache.delete(configSetId)
                        throw SourceAccessFailedException(SourceFailure.REPOSITORY_TOO_LARGE)
                    }
                    tip
                }
            }
        } catch (e: SourceAccessFailedException) {
            log.warn("Git access for ConfigSet {} failed: {}", configSetId, e.failure)
            throw e
        }
    }

    private fun activeKey(
        configSetId: UUID,
        source: SourceDefinition,
    ): CredentialKeyPair =
        try {
            keyPairs.active(source.credentialId)
        } catch (e: CredentialException) {
            throw failed(configSetId, unusable(e))
        }

    private fun failed(
        configSetId: UUID,
        failure: SourceFailure,
    ): SourceAccessFailedException {
        log.warn("Git access for ConfigSet {} failed: {}", configSetId, failure)
        return SourceAccessFailedException(failure)
    }

    private fun requireBranch(
        url: String,
        branch: Branch,
        transport: TransportConfigCallback,
    ) {
        val heads =
            Git
                .lsRemoteRepository()
                .setRemote(url)
                .setHeads(true)
                .setTimeout(TIMEOUT_SECONDS)
                .setTransportConfigCallback(transport)
                .callAsMap()
        if (branch.ref !in heads) throw SourceAccessFailedException(SourceFailure.BRANCH_NOT_FOUND)
    }

    /** Fetches only [branch], into the same ref, and returns the commit ID of its tip. */
    private fun fetchBranch(
        repository: Repository,
        url: String,
        branch: Branch,
        transport: TransportConfigCallback,
        deadline: FetchDeadline,
    ): String {
        Git
            .wrap(repository)
            .fetch()
            .setRemote(url)
            .setRefSpecs(RefSpec("+${branch.ref}:${branch.ref}"))
            .setTagOpt(TagOpt.NO_TAGS)
            .setCheckFetchedObjects(true)
            .setTimeout(TIMEOUT_SECONDS)
            .setProgressMonitor(deadline)
            .setTransportConfigCallback(transport)
            .call()
        return checkNotNull(repository.exactRef(branch.ref)) { "Fetched branch is missing" }.objectId.name
    }

    private fun <T> reading(
        configSetId: UUID,
        revision: RevisionRef,
        read: (Repository) -> T,
    ): T = cache.reading(configSetId, read) ?: throw RevisionNotFoundException(revision)

    private fun resolve(
        repository: Repository,
        source: SourceDefinition,
        revision: RevisionRef,
    ): RevCommit =
        when (revision) {
            RevisionRef.Latest -> {
                val tip = repository.exactRef(source.branch.ref) ?: throw RevisionNotFoundException(revision)
                commitOf(repository, tip.objectId)
            }

            is RevisionRef.Commit -> {
                try {
                    commitOf(repository, ObjectId.fromString(revision.id))
                } catch (_: MissingObjectException) {
                    throw RevisionNotFoundException(revision)
                } catch (_: IncorrectObjectTypeException) {
                    throw RevisionNotFoundException(revision)
                }
            }
        }

    /** The blob of the regular file at [path] beneath the root path at [revision]. */
    private fun blobAt(
        repository: Repository,
        source: SourceDefinition,
        path: SourcePath,
        revision: RevisionRef,
    ): ObjectId =
        regularFileOf(repository, resolve(repository, source, revision), source.rootPath.resolve(path))
            ?: throw SourceFileNotFoundException(path)

    private companion object {
        val log = LoggerFactory.getLogger(SourceAccess::class.java)

        /**
         * JGit's connect timeout and its idle timeout for each read from the connection, not a deadline for a whole
         * fetch; [FetchDeadline] is that, enforced by [SshConnections] (ADR 0033).
         */
        const val TIMEOUT_SECONDS = 30
    }
}

/** Why the credential cannot be used, for the failures `CredentialKeyPairs.active` throws. */
private fun unusable(error: CredentialException): SourceFailure =
    when (error) {
        is CredentialDisabledException -> SourceFailure.CREDENTIAL_DISABLED

        is GitInstanceNotConfiguredException -> SourceFailure.GIT_INSTANCE_NOT_CONFIGURED

        // An existing credential always has an active key, so this is a credential that does not exist.
        is CredentialNotFoundException -> SourceFailure.NO_ACTIVE_KEY

        else -> throw error
    }
