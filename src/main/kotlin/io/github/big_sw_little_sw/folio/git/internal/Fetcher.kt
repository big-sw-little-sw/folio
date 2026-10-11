package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.CredentialKeys
import io.github.big_sw_little_sw.folio.credential.DecryptedKey
import io.github.big_sw_little_sw.folio.credential.KeyId
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.KeyPair

/**
 * The fetch step (design 10.6): it fetches the branch of a Git source into the cache (design 10.5, 10.7). The check
 * calls it. In slice 6, sync and the fetch on demand will call it too. The fetch does these steps, all inside the
 * [FetchDeadline]:
 *
 * 1. It opens the active key of the credential, or the pending key for a check, and finds the Git instance of the key.
 * 2. It waits for the fetch lock of the ConfigSet, so one fetch of each ConfigSet runs at a time in this process.
 * 3. It runs `ls-remote` for the branch. A missing branch fails before Folio changes the cache.
 * 4. It fetches only the branch, without tags.
 * 5. It adds the sizes of the repository files. Above `folio.git.max-repository-size`, it deletes the repository.
 *
 * Each failure of the source throws [GitFailure], and the fetch logs it. The log line has only the ConfigSet ID, the
 * code, the exception types and the duration (design 10.4).
 */
@Component
class Fetcher(
    private val keys: CredentialKeys,
    private val instances: GitInstances,
    private val cache: RepositoryCache,
    private val connections: SshConnections,
    private val properties: GitProperties,
) {
    /**
     * Fetches [source] with the active key of its credential, or with [pendingKey] if it is not null. Returns the
     * commit ID of the tip of the branch. A disabled credential and a Git instance that the configuration lacks are
     * failures of the source, so they throw [GitFailure]. A wrong request throws the error of the credential module:
     * `CredentialNotFound` (404), or `KeyNotPending` (409) if [pendingKey] is not the pending key.
     *
     * The caller must not be in a transaction, because the fetch can take minutes (design 8.3). Without this rule, a
     * fetch holds a database connection for minutes.
     */
    fun fetch(configSet: ConfigSetId, source: GitSourceDefinition, pendingKey: KeyId? = null): ObjectId {
        val started = System.nanoTime()
        // The deadline starts here, so it includes the wait for another fetch of the same ConfigSet.
        val deadline = FetchDeadline(properties.fetchDeadline)
        try {
            return withKey(source.credential, pendingKey) { fetchWith(configSet, source, it, deadline) }
        } catch (failure: GitFailure) {
            logFailure(log, "fetch", configSet, failure, started)
            throw failure
        }
    }

    private fun fetchWith(
        configSet: ConfigSetId,
        source: GitSourceDefinition,
        key: DecryptedKey,
        deadline: FetchDeadline,
    ): ObjectId {
        val instance = instances.find(key.gitInstance) ?: throw GitFailure(FailureCode.GIT_INSTANCE_NOT_CONFIGURED)
        return cache.withFetchLock(configSet, deadline.remaining()) {
            fetchLocked(configSet, source, instance, key.keyPair, deadline)
        } ?: throw GitFailure(FailureCode.DEADLINE_EXCEEDED)
    }

    private fun <T> withKey(credential: CredentialId, pendingKey: KeyId?, block: (DecryptedKey) -> T): T = try {
        if (pendingKey == null) {
            keys.withActiveKey(credential, block)
        } else {
            keys.withPendingKey(credential, pendingKey, block)
        }
    } catch (_: CredentialException.CredentialDisabled) {
        // The fetch runs outside a transaction, so it can catch this error of the credential module (design 8.6).
        throw GitFailure(FailureCode.CREDENTIAL_DISABLED)
    }

    private fun fetchLocked(
        configSet: ConfigSetId,
        source: GitSourceDefinition,
        instance: GitInstance,
        key: KeyPair,
        deadline: FetchDeadline,
    ): ObjectId {
        val url = instance.url(source.repositoryPath)
        val tip =
            connections.run(instance, key, deadline) { transport ->
                requireBranch(url, source.branch, transport)
                cache.fetching(configSet, source.branch, deadline.remaining()) {
                    fetchBranch(it, url, source.branch, transport, deadline)
                }
            }
        // JGit cannot limit a pack during the transfer, so Folio examines the size after the fetch, under the lock. If
        // reads hold the repository until the deadline, the delete fails and the repository stays. The next fetch then
        // examines the size again.
        if (cache.size(configSet) > properties.maxRepositorySize.toBytes()) {
            cache.delete(configSet, deadline.remaining())
            throw GitFailure(FailureCode.REPOSITORY_TOO_LARGE)
        }
        return tip
    }

    private fun requireBranch(url: String, branch: Branch, transport: TransportConfigCallback) {
        val heads =
            Git
                .lsRemoteRepository()
                .setRemote(url)
                .setHeads(true)
                .setTimeout(TIMEOUT_SECONDS)
                .setTransportConfigCallback(transport)
                .callAsMap()
        if (branch.ref !in heads) throw GitFailure(FailureCode.BRANCH_NOT_FOUND)
    }

    /** Fetches only [branch] into the same ref, and returns the commit ID of its tip. */
    private fun fetchBranch(
        repository: Repository,
        url: String,
        branch: Branch,
        transport: TransportConfigCallback,
        deadline: FetchDeadline,
    ): ObjectId {
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
        // A branch that the server deleted between ls-remote and the fetch leaves no ref.
        return repository.exactRef(branch.ref)?.objectId ?: throw GitFailure(FailureCode.BRANCH_NOT_FOUND)
    }

    private companion object {
        val log = LoggerFactory.getLogger(Fetcher::class.java)

        /**
         * The connect timeout of JGit and its timeout for each idle read. It is not a limit for a full fetch. The
         * [FetchDeadline] is that limit.
         */
        const val TIMEOUT_SECONDS = 30
    }
}
