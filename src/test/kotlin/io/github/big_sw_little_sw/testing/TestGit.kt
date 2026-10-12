package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.CredentialKeys
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.git.internal.Branch
import io.github.big_sw_little_sw.folio.git.internal.Fetcher
import io.github.big_sw_little_sw.folio.git.internal.GitInstances
import io.github.big_sw_little_sw.folio.git.internal.GitProperties
import io.github.big_sw_little_sw.folio.git.internal.GitSourceDefinition
import io.github.big_sw_little_sw.folio.git.internal.GitSourceService
import io.github.big_sw_little_sw.folio.git.internal.IsolatedSsh
import io.github.big_sw_little_sw.folio.git.internal.LeaseOwner
import io.github.big_sw_little_sw.folio.git.internal.RepositoryCache
import io.github.big_sw_little_sw.folio.git.internal.RepositoryPath
import io.github.big_sw_little_sw.folio.git.internal.RootPath
import io.github.big_sw_little_sw.folio.git.internal.SyncLease
import io.github.big_sw_little_sw.folio.git.internal.SyncRecorder
import io.github.big_sw_little_sw.folio.git.internal.SyncRepository
import io.github.big_sw_little_sw.folio.git.internal.SyncState
import io.github.big_sw_little_sw.folio.git.internal.Synchronizer
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.uniqueSlug
import org.eclipse.jgit.lib.ObjectId
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistrar
import java.util.UUID

/**
 * Creates Git sources for tests, as a super admin, and syncs them. [sync] runs one sync of one ConfigSet as a poll
 * does. [TestSyncInstances] builds the fetch step and the sync of another instance.
 */
@TestComponent
class TestGit(
    private val sources: GitSourceService,
    private val syncRepository: SyncRepository,
    private val fetcher: Fetcher,
    private val synchronizer: Synchronizer,
    private val cache: RepositoryCache,
    private val catalog: TestCatalog,
    private val credentials: TestCredentials,
    private val jdbc: JdbcClient,
) {
    /**
     * Creates a credential for the Git instance `test-server` and authorizes its active key on [SshGitServer]. With
     * [command], each Git command of the key runs inside [command] (see [SshGitServer.authorize]).
     */
    fun authorizedCredential(namespace: NamespaceId = catalog.namespace(), command: String? = null): Credential {
        val credential = credentials.credential(namespace, gitInstance = "test-server")
        SshGitServer.authorize(credential.activeKey.publicKeyLine, command)
        return credential
    }

    /** Creates a ConfigSet with a Git source for `/repos/<repository>.git`. */
    fun configSet(
        credential: CredentialId,
        repository: String = "configs",
        branch: String = "main",
        rootPath: String = "config",
        namespace: NamespaceId = catalog.namespace(),
    ): ConfigSetId {
        val source =
            GitSourceDefinition(credential, RepositoryPath("repos/$repository.git"), Branch(branch), RootPath(rootPath))
        return sources.create(SUPER_ADMIN, namespace, Slug(uniqueSlug("cfg")), null, source).configSet.id
    }

    /** Creates a ConfigSet for the repository `configs`, with an authorized credential in the same [namespace]. */
    fun connected(namespace: NamespaceId = catalog.namespace()): ConfigSetId =
        configSet(authorizedCredential(namespace).id, namespace = namespace)

    /** Fetches the tip of the branch with the active key through [fetcher], and returns the commit ID of the tip. */
    fun fetchTip(configSet: ConfigSetId, fetcher: Fetcher = this.fetcher): ObjectId =
        fetcher.fetch(configSet, sources.get(SUPER_ADMIN, configSet).source)

    /**
     * Makes [configSet] the only due ConfigSet, then claims it with [synchronizer], as a poll does. Without the
     * [parkAll], the claim could take a Git source of an earlier test.
     */
    fun claim(configSet: ConfigSetId, synchronizer: Synchronizer = this.synchronizer): SyncLease {
        parkAll()
        makeDue(configSet)
        return synchronizer.claim(1).single()
    }

    /** Claims and syncs [configSet] with [synchronizer]. Returns the result of [Synchronizer.sync]. */
    fun sync(configSet: ConfigSetId, synchronizer: Synchronizer = this.synchronizer): Boolean =
        synchronizer.sync(claim(configSet, synchronizer))

    /** Syncs [configSet], requires a success, and returns the last published commit. */
    fun publishTip(configSet: ConfigSetId): String {
        check(sync(configSet)) { "The sync recorded nothing." }
        val failure = state(configSet).failure
        check(failure == null) { "The sync failed with $failure." }
        return checkNotNull(sources.get(SUPER_ADMIN, configSet).lastPublished).id.value
    }

    fun state(configSet: ConfigSetId): SyncState = checkNotNull(syncRepository.state(configSet))

    /** Makes each Git source not due, so a claim of a test takes only the Git sources that the test makes due. */
    fun parkAll() {
        jdbc.sql("update git_source set next_due_at = 'infinity'").update()
    }

    /** Makes [configSet] due now, as a manual sync does. */
    fun makeDue(configSet: ConfigSetId) {
        syncRepository.requestSync(configSet)
    }

    /** True if the due time of [configSet] has come, by the database clock. */
    fun isDue(configSet: ConfigSetId): Boolean = jdbc
        .sql("select next_due_at <= now() from git_source where config_set_id = :id")
        .param("id", configSet.value)
        .query(Boolean::class.java)
        .single()

    /** Ends the lease of [configSet] now, as if its owner stopped. The ConfigSet is then due. */
    fun expireLease(configSet: ConfigSetId) {
        jdbc
            .sql(
                """
                update git_source set lease_end = now() - interval '1 second', next_due_at = now() - interval '1 second'
                where config_set_id = :id
                """.trimIndent(),
            ).param("id", configSet.value)
            .update()
    }

    fun leaseOwner(configSet: ConfigSetId): LeaseOwner? = jdbc
        .sql("select lease_owner from git_source where config_set_id = :id")
        .param("id", configSet.value)
        .query { rs, _ -> rs.getObject("lease_owner", UUID::class.java)?.let(::LeaseOwner) }
        .optional()
        .orElse(null)

    /** Deletes the cached repository of [configSet] on this instance, so the next read fetches it. */
    fun dropCache(configSet: ConfigSetId) {
        check(cache.delete(configSet, TIMEOUT)) { "Reads held the repository" }
    }

    /**
     * Runs [test] while another thread holds the fetch lock of [configSet], so a fetch of it waits. After [test], the
     * holder runs [beforeRelease] with the lock, then releases the lock.
     */
    fun <T> whileFetchLockHeld(configSet: ConfigSetId, beforeRelease: () -> Unit = {}, test: () -> T): T =
        whileHolding({ held ->
            cache.withFetchLock(configSet, TIMEOUT) {
                held()
                beforeRelease()
            }
        }, test)
}

/**
 * Builds the fetch step and the sync of another Folio instance for tests. Each [synchronizer] has its own lease owner.
 * [limits] can set tighter fetch limits than the configuration. This component is the one place in the tests that knows
 * the constructors of [Fetcher] and [Synchronizer].
 */
@TestComponent
class TestSyncInstances(
    private val keys: CredentialKeys,
    private val instances: GitInstances,
    private val cache: RepositoryCache,
    private val ssh: IsolatedSsh,
    private val syncRepository: SyncRepository,
    private val recorder: SyncRecorder,
    private val properties: GitProperties,
) {
    fun fetcher(limits: GitProperties = properties): Fetcher = Fetcher(keys, instances, cache, ssh, limits)

    fun synchronizer(limits: GitProperties = properties): Synchronizer =
        Synchronizer(syncRepository, fetcher(limits), cache, recorder, limits)
}

/** Registers the Git instances of [SshGitServer] in the context. */
@TestConfiguration(proxyBeanMethods = false)
class SshGitServerConfiguration {
    @Bean
    fun sshGitServerProperties() = DynamicPropertyRegistrar(SshGitServer::register)
}
