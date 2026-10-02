package io.github.big_sw_little_sw.folio.configset.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.DuplicateConfigSetSlugException
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.source.Branch
import io.github.big_sw_little_sw.folio.source.RepositoryPath
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import io.github.big_sw_little_sw.folio.source.SourcePath
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ConfigSetRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        namespaceId: NamespaceId,
        slug: Slug,
        source: SourceDefinition,
    ): ConfigSet =
        uniqueSlug(namespaceId, slug) {
            jdbc
                .sql(
                    """
                    insert into config_set (namespace_id, slug, credential_id, repository_path, branch, root_path)
                    values (:namespaceId, :slug, :credentialId, :repositoryPath, :branch, :rootPath)
                    returning $COLUMNS
                    """.trimIndent(),
                ).param("namespaceId", namespaceId.value)
                .param("slug", slug.value)
                .param("credentialId", source.credentialId.value)
                .param("repositoryPath", source.repositoryPath.value)
                .param("branch", source.branch.value)
                .param("rootPath", source.rootPath.value)
                .query { rs, _ -> rs.toConfigSet() }
                .single()
        }

    fun update(configSet: ConfigSet) {
        uniqueSlug(configSet.namespaceId, configSet.slug) {
            jdbc
                .sql("update config_set set namespace_id = :namespaceId, slug = :slug where id = :id")
                .param("id", configSet.id.value)
                .param("namespaceId", configSet.namespaceId.value)
                .param("slug", configSet.slug.value)
                .update()
        }
    }

    /** Deletes the ConfigSet and, by cascade, its rules. */
    fun delete(id: ConfigSetId) {
        jdbc.sql("delete from config_set where id = :id").param("id", id.value).update()
    }

    fun findById(id: ConfigSetId): ConfigSet? =
        jdbc
            .sql("select $COLUMNS from config_set where id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.toConfigSet() }
            .optional()
            .orElse(null)

    fun findBySlug(
        namespaceId: NamespaceId,
        slug: Slug,
    ): ConfigSet? =
        jdbc
            .sql("select $COLUMNS from config_set where namespace_id = :namespaceId and slug = :slug")
            .param("namespaceId", namespaceId.value)
            .param("slug", slug.value)
            .query { rs, _ -> rs.toConfigSet() }
            .optional()
            .orElse(null)

    /** ConfigSets in [namespaceId], ordered by slug. */
    fun findByNamespace(namespaceId: NamespaceId): List<ConfigSet> =
        jdbc
            .sql("select $COLUMNS from config_set where namespace_id = :namespaceId order by slug")
            .param("namespaceId", namespaceId.value)
            .query { rs, _ -> rs.toConfigSet() }
            .list()

    fun findIdsByNamespaces(namespaceIds: List<NamespaceId>): List<ConfigSetId> =
        jdbc
            .sql("select id from config_set where namespace_id in (:namespaceIds)")
            .param("namespaceIds", namespaceIds.map { it.value })
            .query { rs, _ -> ConfigSetId(rs.getObject("id", UUID::class.java)) }
            .list()

    // The slug-per-namespace unique constraint is the only unique key a caller can violate; IDs come from uuidv7().
    private fun <T> uniqueSlug(
        namespaceId: NamespaceId,
        slug: Slug,
        write: () -> T,
    ): T =
        try {
            write()
        } catch (_: DuplicateKeyException) {
            throw DuplicateConfigSetSlugException(namespaceId, slug)
        }

    private fun ResultSet.toConfigSet() =
        ConfigSet(
            id = ConfigSetId(getObject("id", UUID::class.java)),
            namespaceId = NamespaceId(getObject("namespace_id", UUID::class.java)),
            slug = Slug(getString("slug")),
            source =
                SourceDefinition(
                    credentialId = CredentialId(getObject("credential_id", UUID::class.java)),
                    repositoryPath = RepositoryPath(getString("repository_path")),
                    branch = Branch(getString("branch")),
                    rootPath = SourcePath.parse(getString("root_path")),
                ),
        )

    private companion object {
        const val COLUMNS = "id, namespace_id, slug, credential_id, repository_path, branch, root_path"
    }
}
