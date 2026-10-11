package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Subject
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * Runs the queries on the shape of the tree: the tree lock, ancestries, resolve, subtrees and the resources that rules
 * name.
 */
@Repository
class TreeRepository(private val jdbc: JdbcClient) {
    /**
     * Takes the tree lock until the transaction ends (design 8.1). Each catalog write, also a rule write, calls this
     * method first. Thus only one catalog write runs at a time. The tree does not change between the checks of a write
     * and its change. The method needs an existing transaction, because the lock ends with the transaction.
     *
     * Reads take no lock. Instead, each read that authorizes is a [SnapshotRead], so the ancestry and the rules of a
     * decision come from one snapshot. Writes keep `READ COMMITTED`, because their snapshot must start after this lock.
     * Without that, a write would not see the commit of the previous write.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock() {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('folio.catalog.tree', 0))").query().listOfRows()
    }

    /**
     * The ancestry of [resource]. Throws `NamespaceNotFound` or `ConfigSetNotFound` (404) if [resource] does not exist.
     */
    fun requireAncestry(resource: ResourceId): Ancestry = ancestry(resource) ?: throw notFound(resource)

    /** The ancestry of [resource], or null if [resource] does not exist. */
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
                .query { rs, _ -> Ancestry.Link(rs.resource(), Slug(rs.getString("slug"))) }
                .list()
        return links.takeIf { it.isNotEmpty() }?.let(::Ancestry)
    }

    /** The ConfigSet at [path], or null. The last segment of [path] always names the ConfigSet (design 5.3). */
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

    /**
     * Finds each resource with a rule for [action] that names one or more of [subjects]. The result maps the ancestry
     * of each resource to the description of the resource. One query finds the rules through the GIN index on
     * `rule.subjects`. The same query walks up the ancestry of each resource (design 7.4).
     */
    fun withRuleFor(action: Action, subjects: Set<Subject>): Map<Ancestry, String?> {
        if (subjects.isEmpty()) return emptyMap()
        return jdbc
            .sql(GRANTED_CHAINS)
            .param("action", action.name)
            .param("subjects", subjects.map { it.toString() }.toTypedArray())
            .query { rs, _ ->
                ChainRow(
                    granted = rs.resource("granted_id", "granted_config_set"),
                    description = rs.getString("description"),
                    link = Ancestry.Link(rs.resource(), Slug(rs.getString("slug"))),
                )
            }.list()
            .groupBy { it.granted }
            .values
            .associate { rows -> Ancestry(rows.map { it.link }) to rows.first().description }
    }

    /** One link of the ancestry of a [granted] resource. */
    private data class ChainRow(val granted: ResourceId, val description: String?, val link: Ancestry.Link)

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

    /** Reads a resource from an ID column and a boolean column that is true for a ConfigSet. */
    private fun ResultSet.resource(idColumn: String = "id", configSetColumn: String = "config_set"): ResourceId {
        val id = getObject(idColumn, UUID::class.java)
        return if (getBoolean(configSetColumn)) ConfigSetId(id) else NamespaceId(id)
    }

    private companion object {
        /**
         * The query returns each link of the ancestry of each granted resource, from the top level down. Both branches
         * repeat the rule filter, so each branch joins only the table of its own resource type.
         */
        val GRANTED_CHAINS =
            """
            with recursive granted (id, config_set, parent_id, slug, description) as (
                select n.id, false, n.parent_id, n.slug, n.description
                from rule r join namespace n on n.id = r.namespace_id
                where r.action = :action and r.subjects && cast(:subjects as text[])
                union all
                select c.id, true, c.namespace_id, c.slug, c.description
                from rule r join config_set c on c.id = r.config_set_id
                where r.action = :action and r.subjects && cast(:subjects as text[])
            ),
            chain (granted_id, granted_config_set, description, id, parent_id, slug, depth, config_set) as (
                select id, config_set, description, id, parent_id, slug, 0, config_set from granted
                union all
                select chain.granted_id, chain.granted_config_set, chain.description, n.id, n.parent_id, n.slug,
                    chain.depth + 1, false
                from namespace n join chain on n.id = chain.parent_id
            )
            select granted_id, granted_config_set, description, id, slug, config_set from chain
            order by granted_id, granted_config_set, depth desc
            """.trimIndent()
    }
}

private fun notFound(resource: ResourceId) = when (resource) {
    is NamespaceId -> CatalogException.NamespaceNotFound(resource)
    is ConfigSetId -> CatalogException.ConfigSetNotFound(resource)
}
