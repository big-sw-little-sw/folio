package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** A row of `namespace`. The path is not in the row, because a move of an ancestor changes it. */
data class NamespaceRow(val id: NamespaceId, val parentId: NamespaceId?, val slug: Slug, val description: String?)

/** Rows of `namespace`. It owns the unique constraint on sibling slugs, so it changes a violation to [SlugTaken]. */
@Repository
class NamespaceRepository(private val jdbc: JdbcClient) {
    fun insert(parentId: NamespaceId?, slug: Slug, description: String?): NamespaceId = uniqueSlug(slug, parentId) {
        jdbc
            .sql(
                """
                    insert into namespace (parent_id, slug, description) values (:parentId, :slug, :description)
                    returning id
                """.trimIndent(),
            ).param("parentId", parentId?.value)
            .param("slug", slug.value)
            .param("description", description)
            .query(UUID::class.java)
            .single()
            .let(::NamespaceId)
    }

    fun find(id: NamespaceId): NamespaceRow? = jdbc
        .sql("select $COLUMNS from namespace where id = :id")
        .param("id", id.value)
        .query { rs, _ -> rs.toRow() }
        .optional()
        .orElse(null)

    /** The children of [parentId], or the top-level Namespaces if it is null, in slug order. */
    fun children(parentId: NamespaceId?): List<NamespaceRow> = jdbc
        .sql("select $COLUMNS from namespace where parent_id is not distinct from :parentId order by slug")
        .param("parentId", parentId?.value)
        .query { rs, _ -> rs.toRow() }
        .list()

    /** Writes the slug, the description and the parent. The ID stays, so no other table changes (design 16). */
    fun update(row: NamespaceRow) {
        uniqueSlug(row.slug, row.parentId) {
            jdbc
                .sql(
                    """
                    update namespace set parent_id = :parentId, slug = :slug, description = :description
                    where id = :id
                    """.trimIndent(),
                ).param("id", row.id.value)
                .param("parentId", row.parentId?.value)
                .param("slug", row.slug.value)
                .param("description", row.description)
                .update()
        }
    }

    /**
     * Empty means no child Namespaces and no ConfigSets (design 5.4). The credential slice adds credentials to this
     * query: `not exists (select 1 from credential where namespace_id = :id)`.
     */
    fun isEmpty(id: NamespaceId): Boolean = jdbc
        .sql(
            """
                select not exists (select 1 from namespace where parent_id = :id)
                    and not exists (select 1 from config_set where namespace_id = :id)
            """.trimIndent(),
        ).param("id", id.value)
        .query(Boolean::class.java)
        .single()

    /** Its rules go with it (cascade). The caller makes sure that it is empty. */
    fun delete(id: NamespaceId) {
        jdbc.sql("delete from namespace where id = :id").param("id", id.value).update()
    }

    /** IDs come from `uuidv7()`, so the sibling slug is the only unique value that a caller can repeat. */
    private fun <T> uniqueSlug(slug: Slug, parentId: NamespaceId?, write: () -> T): T = try {
        write()
    } catch (_: DuplicateKeyException) {
        throw CatalogException.SlugTaken(slug, parentId?.toApiId() ?: "the top level")
    }

    private fun ResultSet.toRow() = NamespaceRow(
        id = NamespaceId(getObject("id", UUID::class.java)),
        parentId = getObject("parent_id", UUID::class.java)?.let(::NamespaceId),
        slug = Slug(getString("slug")),
        description = getString("description"),
    )

    private companion object {
        const val COLUMNS = "id, parent_id, slug, description"
    }
}
