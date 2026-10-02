package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.credential.KeyStatus
import io.github.big_sw_little_sw.folio.credential.uniqueCredentialName
import io.github.big_sw_little_sw.folio.namespace.Namespace
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import io.github.big_sw_little_sw.folio.source.Branch
import io.github.big_sw_little_sw.folio.source.RepositoryPath
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import io.github.big_sw_little_sw.folio.source.SourcePath
import io.github.big_sw_little_sw.folio.source.SshGitServer
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * ConfigSets on a repository of the SSH Git server (ADR 0004) for sync tests. [reset] runs first in each test and
 * authenticates the thread as a super admin.
 */
class SyncFixture(
    private val configSets: ConfigSetService,
    private val credentials: CredentialService,
    private val namespaces: NamespaceService,
    private val jdbc: JdbcClient,
    private val repository: String,
) {
    private lateinit var namespace: Namespace

    /** Clears ConfigSets and namespaces, recreates the repository and returns its commits, oldest first. */
    fun reset(): List<String> {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
        SshGitServer.revokeAll()
        val commits = SshGitServer.createRepository(repository)
        authenticateAs(SUPER_ADMIN)
        namespace = namespaces.create(null, Slug("production"))
        return commits
    }

    /** A credential of the test server whose active key the server accepts, running commands inside [command]. */
    fun authorizedCredential(command: String? = null): Credential {
        val credential = credentials.create("test-server", uniqueCredentialName())
        authorize(credential, command)
        return credential
    }

    fun authorize(
        credential: Credential,
        command: String? = null,
    ) {
        SshGitServer.authorize(credential.keys.single { it.status == KeyStatus.ACTIVE }.publicKey, command)
    }

    fun configSet(credential: Credential): ConfigSet {
        val source =
            SourceDefinition(
                credential.id,
                RepositoryPath("repos/$repository.git"),
                Branch("main"),
                SourcePath.parse("config"),
            )
        return configSets.create(namespace.id, Slug("cfg-${System.nanoTime()}"), source)
    }

    fun makeDue(configSet: ConfigSet) {
        jdbc
            .sql("update sync_state set next_due_at = now() where config_set_id = :id")
            .param("id", configSet.id.value)
            .update()
    }

    /** Lets the lease on [configSet] run out, as when the instance holding it crashed. */
    fun expireLease(configSet: ConfigSet) {
        jdbc
            .sql(
                """
                update sync_state
                set lease_until = now() - interval '1 second', next_due_at = now() - interval '1 second'
                where config_set_id = :id
                """.trimIndent(),
            ).param("id", configSet.id.value)
            .update()
    }

    fun isDue(configSet: ConfigSet): Boolean =
        jdbc
            .sql("select next_due_at <= now() and lease_owner is null from sync_state where config_set_id = :id")
            .param("id", configSet.id.value)
            .query(Boolean::class.java)
            .single()

    fun leaseOwner(configSet: ConfigSet): String? =
        jdbc
            .sql("select lease_owner::text from sync_state where config_set_id = :id")
            .param("id", configSet.id.value)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun leasedCount(): Int =
        jdbc.sql("select count(*) from sync_state where lease_owner is not null").query(Int::class.java).single()
}
