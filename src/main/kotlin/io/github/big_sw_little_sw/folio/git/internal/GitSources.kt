package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.audit.AuditEntry
import io.github.big_sw_little_sw.folio.audit.AuditLog
import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.catalog.Namespace
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Namespaces
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.catalog.SourceType
import io.github.big_sw_little_sw.folio.content.Revision
import io.github.big_sw_little_sw.folio.credential.Credentials
import io.github.big_sw_little_sw.folio.git.GitException
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A Git ConfigSet with its Source definition and its newest published revision, or null before the first publish. */
data class GitSourceView(val configSet: ConfigSet, val source: GitSourceDefinition, val lastPublished: Revision?)

/**
 * Onboarding and the Git source reads of the admin routes (design 10.3). Each operation needs `MANAGE` on the
 * ConfigSet or, for a create, on its Namespace. A use of a credential also needs `MANAGE` on the Namespace of the
 * credential (design 7.3).
 */
@Service
class GitSources(
    private val configSets: ConfigSets,
    private val namespaces: Namespaces,
    private val credentials: Credentials,
    private val access: Access,
    private val sources: GitSourceRepository,
    private val auditLog: AuditLog,
) {
    /**
     * Creates the ConfigSet and its Git source in one transaction. The create does not connect to the Git server. The
     * catalog writes the `CONFIG_SET_CREATED` record, and this method writes `GIT_SOURCE_CREATED`, so each module
     * records its own operation (design 8.5).
     *
     * [ConfigSets.create] takes the exclusive tree lock. It runs before [Credentials.requireUsable], which authorizes
     * through `Access` with the shared tree lock. This order is the lock-order rule of design 8.1. In the other order,
     * two creates could each hold the shared lock and wait for the exclusive lock.
     */
    @Transactional
    fun create(
        caller: Caller,
        namespace: NamespaceId,
        slug: Slug,
        description: String?,
        source: GitSourceDefinition,
    ): GitSourceView {
        val configSet = configSets.create(caller, namespace, slug, description, SourceType.GIT)
        val credential = credentials.requireUsable(caller, source.credential)
        sources.insert(configSet.id, source)
        auditLog.record(
            AuditEntry(
                caller = caller,
                operation = "GIT_SOURCE_CREATED",
                resourceType = "GIT_SOURCE",
                resourceId = configSet.id.value,
                path = configSet.path.toString(),
                details =
                mapOf(
                    "credentialId" to source.credential.toApiId(),
                    "gitInstance" to credential.gitInstance.value,
                    "repositoryPath" to source.repositoryPath.value,
                    "branch" to source.branch.value,
                    "rootPath" to source.rootPath.value,
                ),
            ),
        )
        return GitSourceView(configSet, source, null)
    }

    /**
     * Returns the Namespace for the UI form that connects a repository. The form needs `MANAGE` on the Namespace, as
     * the create does (design 12.3).
     */
    @SnapshotRead
    fun requireConnectable(caller: Caller, id: NamespaceId): Namespace {
        val namespace = namespaces.find(id) ?: throw CatalogException.NamespaceNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, id)
        return namespace
    }

    /**
     * Returns the Git source of the ConfigSet. The read needs `MANAGE` on the ConfigSet, because the Source definition
     * is administration data. Throws `ConfigSetNotFound` (404), `Forbidden` (403), `Unauthenticated` (401) or
     * `GitSourceNotFound` (404).
     */
    @SnapshotRead
    fun get(caller: Caller, id: ConfigSetId): GitSourceView {
        val (configSet, source) = find(caller, id)
        return GitSourceView(configSet, source, sources.latest(id))
    }

    /**
     * Authorizes a check (design 7.3): `MANAGE` on the ConfigSet and on the Namespace of the credential. Throws the
     * errors of [get], then `Forbidden` or `Unauthenticated` for the credential, then `CredentialDisabled` (409). The
     * check catches `CredentialDisabled` outside this transaction and reports it as a result.
     */
    @SnapshotRead
    fun requireCheckable(caller: Caller, id: ConfigSetId): GitSourceDefinition {
        val (_, source) = find(caller, id)
        credentials.requireUsable(caller, source.credential)
        return source
    }

    private fun find(caller: Caller, id: ConfigSetId): Pair<ConfigSet, GitSourceDefinition> {
        val configSet = configSets.find(id) ?: throw CatalogException.ConfigSetNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, id)
        val source = sources.find(id) ?: throw GitException.GitSourceNotFound(id)
        return configSet to source
    }
}
