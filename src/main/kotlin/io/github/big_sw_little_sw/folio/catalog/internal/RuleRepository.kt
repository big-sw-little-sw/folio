package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Subject
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * A grant of one action to one or more subjects. [RuleRepository] stores each rule on one resource. The constructor
 * throws [CatalogException.NoSubjects], so a request without subjects fails with 400 before Folio finds the resource.
 */
data class Rule(val action: Action, val subjects: Set<Subject>) {
    init {
        if (subjects.isEmpty()) throw CatalogException.NoSubjects()
    }
}

/**
 * Reads and writes the rows of the `rule` table. Each row names its resource in one column: `namespace_id` or
 * `config_set_id`.
 */
@Repository
class RuleRepository(private val jdbc: JdbcClient) {
    /** The subjects of the rules for [action] on [resources], for each resource that has such a rule. */
    fun subjects(action: Action, resources: Collection<ResourceId>): Map<ResourceId, Set<Subject>> {
        if (resources.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                select namespace_id, config_set_id, subjects from rule
                where action = :action and (namespace_id in (:ids) or config_set_id in (:ids))
                """.trimIndent(),
            ).param("action", action.name)
            .param("ids", resources.map { it.value })
            .query { rs, _ -> rs.target() to rs.subjects() }
            .list()
            // The query looks for each UUID in both columns. A Namespace and a ConfigSet can have the same UUID.
            // Without this filter, the rule of a resource that the caller did not ask for gets into the result.
            .filter { it.first in resources }
            .toMap()
    }

    /** The own rules of [resource], in action order. */
    fun rules(resource: ResourceId): List<Rule> = jdbc
        .sql("select action, subjects from rule where ${resource.column()} = :id")
        .param("id", resource.value)
        .query { rs, _ -> Rule(Action.valueOf(rs.getString("action")), rs.subjects()) }
        .list()
        .sortedBy { it.action }

    fun anyOn(resource: ResourceId): Boolean = jdbc
        .sql("select exists (select 1 from rule where ${resource.column()} = :id)")
        .param("id", resource.value)
        .query(Boolean::class.java)
        .single()

    /** Inserts or replaces the rule of [resource] for the action of [rule]. */
    fun put(resource: ResourceId, rule: Rule) {
        val column = resource.column()
        jdbc
            .sql(
                """
                insert into rule ($column, action, subjects) values (:id, :action, :subjects)
                on conflict ($column, action) do update set subjects = excluded.subjects
                """.trimIndent(),
            ).param("id", resource.value)
            .param("action", rule.action.name)
            .param("subjects", rule.subjects.map { it.toString() }.toTypedArray())
            .update()
    }

    fun delete(resource: ResourceId, action: Action) {
        jdbc
            .sql("delete from rule where ${resource.column()} = :id and action = :action")
            .param("id", resource.value)
            .param("action", action.name)
            .update()
    }

    private fun ResourceId.column() = when (this) {
        is NamespaceId -> "namespace_id"
        is ConfigSetId -> "config_set_id"
    }

    private fun ResultSet.target(): ResourceId = getObject("namespace_id", UUID::class.java)?.let(::NamespaceId)
        ?: ConfigSetId(getObject("config_set_id", UUID::class.java))

    private fun ResultSet.subjects(): Set<Subject> =
        (getArray("subjects").array as Array<*>).map { Subject.parse(it as String) }.toSet()
}
