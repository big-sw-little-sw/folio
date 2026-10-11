package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.git.internal.Branch
import io.github.big_sw_little_sw.folio.git.internal.Fetcher
import io.github.big_sw_little_sw.folio.git.internal.GitSourceDefinition
import io.github.big_sw_little_sw.folio.git.internal.GitSourceRepository
import io.github.big_sw_little_sw.folio.git.internal.GitSources
import io.github.big_sw_little_sw.folio.git.internal.RepositoryPath
import io.github.big_sw_little_sw.folio.git.internal.RootPath
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.uniqueSlug
import org.eclipse.jgit.lib.ObjectId
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar

/**
 * This component sets up Git sources for tests, as a super admin. Slice 6 adds sync (design 20). Until then,
 * [publishTip] publishes revisions through the internal fetch and publish steps that sync will call.
 */
@TestComponent
class TestGit(
    private val sources: GitSources,
    private val repository: GitSourceRepository,
    private val fetcher: Fetcher,
    private val catalog: TestCatalog,
    private val credentials: TestCredentials,
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

    /** Fetches the tip of the branch with the active key through [fetcher], and returns the commit ID of the tip. */
    fun fetchTip(configSet: ConfigSetId, fetcher: Fetcher = this.fetcher): ObjectId =
        fetcher.fetch(configSet, checkNotNull(repository.find(configSet)))

    /** Fetches the tip of the branch with the active key and publishes it, as sync will do. Returns the commit ID. */
    fun publishTip(configSet: ConfigSetId): String {
        val tip = fetchTip(configSet)
        repository.publish(configSet, tip)
        return tip.name
    }
}

/** This configuration registers the Git instances of [SshGitServer] in the context (design 17). */
@TestConfiguration(proxyBeanMethods = false)
class SshGitServerConfiguration {
    @Bean
    fun sshGitServerProperties() = DynamicPropertyRegistrar(SshGitServer::register)
}
