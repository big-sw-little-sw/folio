package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Queries on the shape of the tree: the tree lock, ancestries, resolve and subtrees. */
@Repository
class TreeRepository(private val jdbc: JdbcClient) {
    /**
     * Takes the one tree lock until the transaction ends (design 8.1). Each catalog write, also a rule write, calls
     * this first. Thus the tree does not change between the checks of a write and its change.
     *
     * Reads take no lock. Each read that authorizes runs in one read-only `REPEATABLE READ` transaction instead, so the
     * ancestry and the rules of a decision come from one snapshot. Writes keep `READ COMMITTED`: their snapshot must
     * start after this lock, so that they see the commit of the previous writer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock() {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('folio.catalog.tree', 0))").query().listOfRows()
    }

    /** The ancestry of [resource], or null if it does not exist. */
    fun ancestry(resource: ResourceId): Ancestry? {
        val start =
            when (resource) {
                is NamespaceId -> "select id, parent_id, slug, 0, false from namespace where id = :id"
                is ConfigSetId -> "select id, namespace_id, slug, 0, true from config_set where id = :id"
            }
        val links =
            jdbc
                .sql(
                    """
                    with recursive chain (id, parent_id, slug, depth, config_set) as (
                        $start
                        union all
                        select n.id, n.parent_id, n.slug, chain.depth + 1, false
                        from namespace n join chain on n.id = chain.parent_id
                    )
                    select id, slug, config_set from chain order by depth desc
                    """.trimIndent(),
                ).param("id", resource.value)
                .query { rs, _ ->
                    val id = rs.getObject("id", UUID::class.java)
                    val linked = if (rs.getBoolean("config_set")) ConfigSetId(id) else NamespaceId(id)
                    Ancestry.Link(linked, Slug(rs.getString("slug")))
                }.list()
        return links.takeIf { it.isNotEmpty() }?.let(::Ancestry)
    }

    /** The ConfigSet at [path], or null. The last segment is always the ConfigSet (design 5.3). */
    fun resolve(path: Path): ConfigSetId? {
        val namespaces = path.slugs.dropLast(1)
        return jdbc
            .sql(
                """
                with recursive walk (id, depth) as (
                    select id, 1 from namespace where parent_id is null and slug = (string_to_array(:namespaces, '/'))[1]
                    union all
                    select n.id, walk.depth + 1
                    from namespace n join walk on n.parent_id = walk.id
                    where walk.depth < :depth and n.slug = (string_to_array(:namespaces, '/'))[walk.depth + 1]
                )
                select c.id from config_set c join walk on c.namespace_id = walk.id
                where walk.depth = :depth and c.slug = :slug
                """.trimIndent(),
            ).param("namespaces", namespaces.joinToString("/"))
            .param("depth", namespaces.size)
            .param("slug", path.slugs.last().value)
            .query(UUID::class.java)
            .optional()
            .map(::ConfigSetId)
            .orElse(null)
    }

    /** True if [namespace], a Namespace below it, or a ConfigSet in one of them has a rule. */
    fun subtreeHasRules(namespace: NamespaceId): Boolean = jdbc
        .sql(
            """
                with recursive subtree (id) as (
                    select cast(:id as uuid)
                    union all
                    select n.id from namespace n join subtree on n.parent_id = subtree.id
                )
                select exists (
                    select 1 from rule where namespace_id in (select id from subtree)
                    union all
                    select 1 from rule join config_set c on c.id = rule.config_set_id
                    where c.namespace_id in (select id from subtree)
                )
            """.trimIndent(),
        ).param("id", namespace.value)
        .query(Boolean::class.java)
        .single()
}

/** The failure for a [resource] that does not exist. */
fun notFound(resource: ResourceId) = when (resource) {
    is NamespaceId -> CatalogException.NamespaceNotFound(resource)
    is ConfigSetId -> CatalogException.ConfigSetNotFound(resource)
}
