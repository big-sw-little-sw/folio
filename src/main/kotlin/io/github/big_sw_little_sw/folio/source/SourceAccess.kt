package io.github.big_sw_little_sw.folio.source

import io.github.big_sw_little_sw.folio.credential.CredentialKeyPair
import io.github.big_sw_little_sw.folio.credential.CredentialKeyPairs
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.source.internal.IsolatedSystemReader
import io.github.big_sw_little_sw.folio.source.internal.RepositoryCache
import io.github.big_sw_little_sw.folio.source.internal.SshConnections
import io.github.big_sw_little_sw.folio.source.internal.sshUrl
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Git access for ConfigSet sources (ADR 0024): the onboarding check, fetching the branch into the ConfigSet's
 * cached bare repository, and reads beneath the root path. Nothing here authorizes; callers do. Reads use only
 * the cache and never contact the Git service.
 *
 * [configSetId] keys the cache. Logs carry at most a ConfigSet ID and a failure code (ADR 0027).
 */
@Service
class SourceAccess(
    private val keyPairs: CredentialKeyPairs,
    private val connections: SshConnections,
    private val cache: RepositoryCache,
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
        val rootFound = cache.reading(configSetId) { isDirectory(it, ObjectId.fromString(commitId), source.rootPath) }
        if (rootFound == true) return SourceCheck.Passed(commitId)
        log.warn("Source check of ConfigSet {} failed: {}", configSetId, SourceFailure.ROOT_PATH_NOT_FOUND)
        return SourceCheck.Failed(SourceFailure.ROOT_PATH_NOT_FOUND)
    }

    /**
     * Fetches the source's branch into the cache with the credential's active key and returns the commit ID of its
     * tip. Throws [SourceAccessFailedException] if Git access fails.
     */
    fun fetch(
        configSetId: UUID,
        source: SourceDefinition,
    ): String = fetch(configSetId, source, keyPairs.active(source.credentialId))

    /** The regular files beneath the root path at [revision], relative to it, in Git's tree order. */
    fun list(
        configSetId: UUID,
        source: SourceDefinition,
        revision: RevisionRef,
    ): List<SourcePath> =
        reading(configSetId, revision) { repository ->
            val commit = resolve(repository, source, revision)
            val root = subtree(repository, commit, source.rootPath) ?: return@reading emptyList()
            TreeWalk(repository).use { walk ->
                walk.addTree(root)
                walk.isRecursive = true
                // Git allows names that are not valid paths here, such as ones with a backslash; they cannot be read.
                generateSequence { if (walk.next()) walk else null }
                    .filter { it.getFileMode(0).isRegularFile() }
                    .mapNotNull { SourcePath.parseOrNull(it.pathString) }
                    .toList()
            }
        }

    /** The raw bytes of the regular file at [path] beneath the root path at [revision]. */
    fun read(
        configSetId: UUID,
        source: SourceDefinition,
        path: SourcePath,
        revision: RevisionRef,
    ): ByteArray =
        reading(configSetId, revision) { repository ->
            val commit = resolve(repository, source, revision)
            val walk =
                TreeWalk.forPath(repository, source.rootPath.resolve(path).value, commit.tree)
                    ?: throw SourceFileNotFoundException(path)
            walk.use {
                if (!it.getFileMode(0).isRegularFile()) throw SourceFileNotFoundException(path)
                repository.open(it.getObjectId(0), Constants.OBJ_BLOB).bytes
            }
        }

    /** ls-remote first, so that a missing branch is reported as such and nothing is fetched on failure. */
    private fun fetch(
        configSetId: UUID,
        source: SourceDefinition,
        credential: CredentialKeyPair,
    ): String {
        val url = sshUrl(credential.gitInstance, source.repositoryPath)
        try {
            return connections.run(credential) { transport ->
                requireBranch(url, source.branch, transport)
                cache.fetching(configSetId, source.branch) { fetchBranch(it, url, source.branch, transport) }
            }
        } catch (e: SourceAccessFailedException) {
            // A failure that is not one of the known causes may come from a damaged cache; start the next one afresh.
            if (e.failure == SourceFailure.TRANSPORT_FAILURE) cache.discard(configSetId)
            log.warn("Git access for ConfigSet {} failed: {}", configSetId, e.failure)
            throw e
        }
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
    ): String {
        Git
            .wrap(repository)
            .fetch()
            .setRemote(url)
            .setRefSpecs(RefSpec("+${branch.ref}:${branch.ref}"))
            .setTagOpt(TagOpt.NO_TAGS)
            .setTimeout(TIMEOUT_SECONDS)
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
                commit(repository, tip.objectId)
            }

            is RevisionRef.Commit -> {
                try {
                    commit(repository, ObjectId.fromString(revision.id))
                } catch (_: MissingObjectException) {
                    throw RevisionNotFoundException(revision)
                } catch (_: IncorrectObjectTypeException) {
                    throw RevisionNotFoundException(revision)
                }
            }
        }

    private fun commit(
        repository: Repository,
        id: ObjectId,
    ): RevCommit = RevWalk(repository).use { it.parseCommit(id) }

    private fun isDirectory(
        repository: Repository,
        commitId: ObjectId,
        path: SourcePath,
    ) = subtree(repository, commit(repository, commitId), path) != null

    /** The tree at [path] in [commit], or null if [path] is missing or not a directory. */
    private fun subtree(
        repository: Repository,
        commit: RevCommit,
        path: SourcePath,
    ): ObjectId? {
        if (path.isRoot) return commit.tree
        return TreeWalk
            .forPath(
                repository,
                path.value,
                commit.tree,
            )?.use { if (it.isSubtree) it.getObjectId(0) else null }
    }

    private fun FileMode.isRegularFile() = this == FileMode.REGULAR_FILE || this == FileMode.EXECUTABLE_FILE

    private companion object {
        val log = LoggerFactory.getLogger(SourceAccess::class.java)

        /** Bounds each connection's setup and every read from it (design 23.2). */
        const val TIMEOUT_SECONDS = 30
    }
}
