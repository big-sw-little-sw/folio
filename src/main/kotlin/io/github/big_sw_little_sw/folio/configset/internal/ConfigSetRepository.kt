package io.github.big_sw_little_sw.folio.configset.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.DuplicateConfigSetSlugException
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.Slug
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
    ): ConfigSet =
        uniqueSlug(namespaceId, slug) {
            jdbc
                .sql("insert into config_set (namespace_id, slug) values (:namespaceId, :slug) returning $COLUMNS")
                .param("namespaceId", namespaceId.value)
                .param("slug", slug.value)
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
        )

    private companion object {
        const val COLUMNS = "id, namespace_id, slug"
    }
}
