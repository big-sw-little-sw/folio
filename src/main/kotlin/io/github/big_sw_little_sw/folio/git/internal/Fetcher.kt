package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.CredentialKeys
import io.github.big_sw_little_sw.folio.credential.DecryptedKey
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.micrometer.observation.ObservationRegistry
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
 * Fetches the branch of a Git source into the cache, within the [FetchDeadline]. Each failure of the source throws
 * [GitFailure], and the fetcher logs the failure with [logFailure].
 */
@Component
class Fetcher(
    private val keys: CredentialKeys,
    private val instances: GitInstances,
    private val cache: RepositoryCache,
    private val ssh: IsolatedSsh,
    private val properties: GitProperties,
    private val observations: ObservationRegistry,
) {
    /**
     * Fetches [source] with the active key of its credential, or with [pendingKey] if it is not null. Returns the
     * commit ID of the tip of the branch. A disabled credential and a Git instance that the configuration lacks are
     * failures of the source, so they throw [GitFailure]. A wrong request throws the error of the credential module:
     * `CredentialNotFound` (404), or `KeyNotPending` (409) if [pendingKey] is not the pending key.
     *
     * The [deadline] starts before the fetch lock, so it includes the wait for another fetch of the same ConfigSet.
     * A caller can already hold the fetch lock ([RepositoryCache.withFetchLock]).
     *
     * The caller must not be in a transaction. Without this rule, a fetch holds a database connection for minutes.
     */
    fun fetch(
        configSet: ConfigSetId,
        source: GitSourceDefinition,
        pendingKey: KeyId? = null,
        deadline: FetchDeadline = FetchDeadline(properties.fetchDeadline),
    ): ObjectId = try {
        withInstanceKey(source, pendingKey) { instance, key ->
            cache.withFetchLock(configSet, deadline.remaining()) {
                fetchLocked(configSet, source, instance, key, deadline)
            } ?: throw GitFailure(FailureCode.DEADLINE_EXCEEDED)
        }
    } catch (failure: GitFailure) {
        logFailure(log, "fetch", configSet, failure, deadline.started)
        throw failure
    }

    /** Opens the key of the credential and finds the Git instance of the key, then runs [block] with both. */
    private fun <T> withInstanceKey(
        source: GitSourceDefinition,
        pendingKey: KeyId?,
        block: (GitInstance, KeyPair) -> T,
    ): T {
        val withKey = { key: DecryptedKey ->
            val instance = instances.find(key.gitInstance) ?: throw GitFailure(FailureCode.GIT_INSTANCE_NOT_CONFIGURED)
            block(instance, key.keyPair)
        }
        return try {
            if (pendingKey == null) {
                keys.withActiveKey(source.credential, withKey)
            } else {
                keys.withPendingKey(source.credential, pendingKey, withKey)
            }
        } catch (_: CredentialException.CredentialDisabled) {
            // The fetch runs outside a transaction, so this error of the credential module marks no transaction for
            // rollback.
            throw GitFailure(FailureCode.CREDENTIAL_DISABLED)
        }
    }

    private fun fetchLocked(
        configSet: ConfigSetId,
        source: GitSourceDefinition,
        instance: GitInstance,
        key: KeyPair,
        deadline: FetchDeadline,
    ): ObjectId {
        val url = instance.url(source.repositoryPath)
        return observations.fetchSpan(configSet) {
            val tip =
                ssh.run(instance, key, deadline) { transport ->
                    requireBranch(url, source.branch, transport)
                    cache.fetching(configSet, source.branch, deadline.remaining()) {
                        fetchBranch(it, url, source.branch, transport, deadline)
                    }
                }
            // JGit cannot limit a pack during the transfer, so Folio examines the size after the fetch, under the lock.
            // If reads hold the repository until the deadline, the delete fails and the repository stays. The next
            // fetch then examines the size again.
            if (cache.size(configSet) > properties.maxRepositorySize.toBytes()) {
                cache.delete(configSet, deadline.remaining())
                throw GitFailure(FailureCode.REPOSITORY_TOO_LARGE)
            }
            tip
        }
    }

    /** Runs before the fetch, so a missing branch fails before Folio changes the cache. */
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

    /**
     * Fetches only [branch], without tags, so the cache holds no content that the Source definition does not name.
     * The forced ref spec makes the cache follow a force push too. Returns the commit ID of the tip.
     */
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

        /** The connect timeout of JGit and its timeout for each idle read. [FetchDeadline] limits the full fetch. */
        const val TIMEOUT_SECONDS = 30
    }
}
