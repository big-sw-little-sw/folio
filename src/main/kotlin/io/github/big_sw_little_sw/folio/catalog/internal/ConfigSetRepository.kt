package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SourceType
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** A row of the `config_set` table. The row has no path, because a rename or a move of an ancestor changes the path. */
data class ConfigSetRow(
    val id: ConfigSetId,
    val namespaceId: NamespaceId,
    val slug: Slug,
    val description: String?,
    val sourceType: SourceType,
) {
    fun toConfigSet(path: Path) = ConfigSet(id, namespaceId, slug, description, sourceType, path)
}

/**
 * Reads and writes the rows of the `config_set` table. The repository owns the unique constraint on slugs in a
 * Namespace. Thus the repository changes a violation to [CatalogException.SlugTaken].
 */
@Repository
class ConfigSetRepository(private val jdbc: JdbcClient) {
    fun insert(namespaceId: NamespaceId, slug: Slug, description: String?, sourceType: SourceType): ConfigSetId =
        uniqueSlug(slug, namespaceId) {
            jdbc
                .sql(
                    """
                    insert into config_set (namespace_id, slug, description, source_type)
                    values (:namespaceId, :slug, :description, :sourceType)
                    returning id
                    """.trimIndent(),
                ).param("namespaceId", namespaceId.value)
                .param("slug", slug.value)
                .param("description", description)
                .param("sourceType", sourceType.name)
                .query(UUID::class.java)
                .single()
                .let(::ConfigSetId)
        }

    fun find(id: ConfigSetId): ConfigSetRow? = jdbc
        .sql("select $COLUMNS from config_set where id = :id")
        .param("id", id.value)
        .query { rs, _ -> rs.toRow() }
        .optional()
        .orElse(null)

    /** The ConfigSets of [namespaceId], in slug order. */
    fun inNamespace(namespaceId: NamespaceId): List<ConfigSetRow> = jdbc
        .sql("select $COLUMNS from config_set where namespace_id = :namespaceId order by slug")
        .param("namespaceId", namespaceId.value)
        .query { rs, _ -> rs.toRow() }
        .list()

    /**
     * Writes the slug, the description and the Namespace. The Source type never changes, so the published revisions of
     * the ConfigSet stay the same.
     */
    fun update(row: ConfigSetRow) {
        uniqueSlug(row.slug, row.namespaceId) {
            jdbc
                .sql(
                    """
                    update config_set set namespace_id = :namespaceId, slug = :slug, description = :description
                    where id = :id
                    """.trimIndent(),
                ).param("id", row.id.value)
                .param("namespaceId", row.namespaceId.value)
                .param("slug", row.slug.value)
                .param("description", row.description)
                .update()
        }
    }

    /**
     * Deletes the ConfigSet. Foreign keys that cascade from `config_set` delete its rules and its rows in the tables of
     * other modules, for example the Source definition and the revisions.
     */
    fun delete(id: ConfigSetId) {
        jdbc.sql("delete from config_set where id = :id").param("id", id.value).update()
    }

    private fun <T> uniqueSlug(slug: Slug, namespaceId: NamespaceId, write: () -> T): T = try {
        write()
    } catch (_: DuplicateKeyException) {
        throw CatalogException.SlugTaken(slug, namespaceId)
    }

    private fun ResultSet.toRow() = ConfigSetRow(
        id = ConfigSetId(getObject("id", UUID::class.java)),
        namespaceId = NamespaceId(getObject("namespace_id", UUID::class.java)),
        slug = Slug(getString("slug")),
        description = getString("description"),
        sourceType = SourceType.valueOf(getString("source_type")),
    )

    private companion object {
        const val COLUMNS = "id, namespace_id, slug, description, source_type"
    }
}
