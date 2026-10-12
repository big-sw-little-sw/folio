package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.Namespace
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.Slug
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** A row of the `namespace` table. The row has no path, because a rename or a move of an ancestor changes the path. */
data class NamespaceRow(val id: NamespaceId, val parentId: NamespaceId?, val slug: Slug, val description: String?) {
    fun toNamespace(path: Path) = Namespace(id, parentId, slug, description, path)
}

/**
 * Reads and writes the rows of the `namespace` table. The repository owns the unique constraint on sibling slugs. Thus
 * the repository changes a violation to [CatalogException.SlugTaken].
 */
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

    /** The children of [parentId], or the top-level Namespaces if [parentId] is null, in slug order. */
    fun children(parentId: NamespaceId?): List<NamespaceRow> = jdbc
        .sql("select $COLUMNS from namespace where parent_id is not distinct from :parentId order by slug")
        .param("parentId", parentId?.value)
        .query { rs, _ -> rs.toRow() }
        .list()

    /** Writes the slug, the description and the parent. The ID does not change, so no other table changes. */
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
     * True if the Namespace has no child Namespaces, no ConfigSets and no credentials. The `credential` module owns the
     * `credential` table. The catalog reads that table only in this query, so the catalog does not depend on the
     * `credential` module.
     */
    fun isEmpty(id: NamespaceId): Boolean = jdbc
        .sql(
            """
                select not exists (select 1 from namespace where parent_id = :id)
                    and not exists (select 1 from config_set where namespace_id = :id)
                    and not exists (select 1 from credential where namespace_id = :id)
            """.trimIndent(),
        ).param("id", id.value)
        .query(Boolean::class.java)
        .single()

    /**
     * Deletes the Namespace, and a foreign key that cascades deletes its rules. The service must call [isEmpty] first,
     * under the tree lock. The tree lock stops a parallel create of a child Namespace, a ConfigSet or a credential,
     * because a credential create holds the shared form of the lock. The foreign key from `credential` is a second
     * check: if a credential row exists after [isEmpty], the delete fails, and this method changes that violation to
     * [CatalogException.NamespaceNotEmpty].
     */
    fun delete(id: NamespaceId) {
        try {
            jdbc.sql("delete from namespace where id = :id").param("id", id.value).update()
        } catch (_: DataIntegrityViolationException) {
            throw CatalogException.NamespaceNotEmpty(id)
        }
    }

    /**
     * IDs come from `uuidv7()`, so the sibling slug is the only unique value that a caller can repeat. Thus a duplicate
     * key always means that a sibling has the slug.
     */
    private fun <T> uniqueSlug(slug: Slug, parentId: NamespaceId?, write: () -> T): T = try {
        write()
    } catch (_: DuplicateKeyException) {
        throw CatalogException.SlugTaken(slug, parentId)
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
