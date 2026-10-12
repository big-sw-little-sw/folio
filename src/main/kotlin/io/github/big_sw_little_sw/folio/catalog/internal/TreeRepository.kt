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
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.util.UUID

@Repository
class TreeRepository(private val jdbc: JdbcClient) {
    /**
     * Takes the tree lock until the transaction ends. Each catalog write, including a rule write, takes the lock before
     * it reads the tree. Thus only one catalog write runs at a time. A write examines the tree and then changes it.
     * Without the lock, a parallel write could change the tree between these two steps. For example, two moves could
     * make a cycle. Reads take no lock, because each read that authorizes is a [SnapshotRead].
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock() {
        jdbc.sql("select pg_advisory_xact_lock($TREE_LOCK)").query().listOfRows()
    }

    /**
     * Takes the shared form of the tree lock until the transaction ends. The shared form waits for a running catalog
     * write and blocks the next one. Thus a write outside the catalog reads the ancestry and the rules from one
     * committed tree. PostgreSQL does not block a transaction on its own locks, so a catalog write that holds [lock]
     * does not wait here. A read-only transaction is a [SnapshotRead], and its snapshot already covers the reads, so
     * the method takes no lock there.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockShared() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return
        jdbc.sql("select pg_advisory_xact_lock_shared($TREE_LOCK)").query().listOfRows()
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

    /**
     * The ConfigSet at [path], or null. The last segment always names a ConfigSet. Thus a Namespace and a ConfigSet can
     * have the same slug in one parent.
     */
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
     * `rule.subjects` and walks up the ancestry of each resource.
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
         * The key of the tree lock. The exclusive form and the shared form use the same key, so they block each other.
         */
        const val TREE_LOCK = "hashtextextended('folio.catalog.tree', 0)"

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
