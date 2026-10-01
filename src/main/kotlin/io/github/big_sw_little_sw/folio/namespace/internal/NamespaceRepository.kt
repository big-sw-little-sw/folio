package io.github.big_sw_little_sw.folio.namespace.internal

import io.github.big_sw_little_sw.folio.namespace.DuplicateSlugException
import io.github.big_sw_little_sw.folio.namespace.Namespace
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.Slug
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class NamespaceRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        parentId: NamespaceId?,
        slug: Slug,
    ): Namespace =
        uniqueSiblingSlug(parentId, slug) {
            jdbc
                .sql("insert into namespace (parent_id, slug) values (:parentId, :slug) returning $COLUMNS")
                .param("parentId", parentId?.value)
                .param("slug", slug.value)
                .query { rs, _ -> rs.toNamespace() }
                .single()
        }

    fun update(namespace: Namespace) {
        uniqueSiblingSlug(namespace.parentId, namespace.slug) {
            jdbc
                .sql("update namespace set parent_id = :parentId, slug = :slug where id = :id")
                .param("id", namespace.id.value)
                .param("parentId", namespace.parentId?.value)
                .param("slug", namespace.slug.value)
                .update()
        }
    }

    fun delete(id: NamespaceId) {
        jdbc.sql("delete from namespace where id = :id").param("id", id.value).update()
    }

    fun findById(id: NamespaceId): Namespace? =
        jdbc
            .sql("select $COLUMNS from namespace where id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.toNamespace() }
            .optional()
            .orElse(null)

    /** Children of [parentId], or the root namespaces if it is null; ordered by slug. */
    fun findChildren(parentId: NamespaceId?): List<Namespace> =
        jdbc
            .sql("select $COLUMNS from namespace where parent_id is not distinct from :parentId order by slug")
            .param("parentId", parentId?.value)
            .query { rs, _ -> rs.toNamespace() }
            .list()

    fun hasChildren(id: NamespaceId): Boolean =
        jdbc
            .sql("select exists (select 1 from namespace where parent_id = :id)")
            .param("id", id.value)
            .query(Boolean::class.java)
            .single()

    /** Ancestors of [id], root first, without [id] itself. */
    fun findAncestors(id: NamespaceId): List<Namespace> =
        jdbc
            .sql(
                """
                select n.id, n.parent_id, n.slug
                from namespace_closure c
                join namespace n on n.id = c.ancestor_id
                where c.descendant_id = :id and c.depth > 0
                order by c.depth desc
                """.trimIndent(),
            ).param("id", id.value)
            .query { rs, _ -> rs.toNamespace() }
            .list()

    // The sibling-slug unique constraint is the only unique key a caller can violate; IDs come from uuidv7().
    private fun <T> uniqueSiblingSlug(
        parentId: NamespaceId?,
        slug: Slug,
        write: () -> T,
    ): T =
        try {
            write()
        } catch (_: DuplicateKeyException) {
            throw DuplicateSlugException(parentId, slug)
        }

    private fun ResultSet.toNamespace() =
        Namespace(
            id = NamespaceId(getObject("id", UUID::class.java)),
            parentId = getObject("parent_id", UUID::class.java)?.let(::NamespaceId),
            slug = Slug(getString("slug")),
        )

    private companion object {
        const val COLUMNS = "id, parent_id, slug"
    }
}
