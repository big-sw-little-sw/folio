package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * Rows of `policy_rule` and `policy_subject`. A rule always has at least one subject and belongs to exactly one
 * namespace or ConfigSet, held in `namespace_id` or `config_set_id`.
 */
@Repository
class PolicyRuleRepository(
    private val jdbc: JdbcClient,
) {
    /** Subjects of the rules for [action] on any resource in [path], keyed by resource. */
    fun findSubjects(
        action: Action,
        path: List<ResourceRef>,
    ): Map<ResourceRef, List<Subject>> {
        if (path.isEmpty()) return emptyMap()
        // One ID list for both columns keeps the query simple. A row typed differently from its path entry
        // would only add an unused map key: callers look rules up by typed reference.
        return jdbc
            .sql(
                """
                select r.namespace_id, r.config_set_id, s.subject_type, s.external_id
                from policy_rule r
                join policy_subject s on s.policy_rule_id = r.id
                where r.action = :action and (r.namespace_id in (:ids) or r.config_set_id in (:ids))
                order by s.subject_type, s.external_id
                """.trimIndent(),
            ).param("action", action.name)
            .param("ids", path.map { it.id })
            .query { rs, _ -> rs.toResourceRef() to rs.toSubject() }
            .list()
            .groupBy({ it.first }, { it.second })
    }

    /** Whether any of [resources] has a rule of its own, for any action. */
    fun existsOnAny(resources: Collection<ResourceRef>): Boolean =
        resources.groupBy { it.column() }.any { (column, refs) ->
            jdbc
                .sql("select exists (select 1 from policy_rule where $column in (:ids))")
                .param("ids", refs.map { it.id })
                .query(Boolean::class.java)
                .single()
        }

    /** Rules on [resource], ordered by action name. */
    fun findByResource(resource: ResourceRef): List<Rule> =
        jdbc
            .sql(
                """
                select r.action, s.subject_type, s.external_id
                from policy_rule r
                join policy_subject s on s.policy_rule_id = r.id
                where r.${resource.column()} = :resourceId
                order by r.action, s.subject_type, s.external_id
                """.trimIndent(),
            ).param("resourceId", resource.id)
            .query { rs, _ -> Action.valueOf(rs.getString("action")) to rs.toSubject() }
            .list()
            .groupBy({ it.first }, { it.second })
            .map { (action, subjects) -> Rule(action, subjects.toSet()) }

    /** Replaces the rule for the same action on [resource], if any. */
    fun put(
        resource: ResourceRef,
        rule: Rule,
    ) {
        delete(resource, rule.action)
        val ruleId =
            jdbc
                .sql(
                    "insert into policy_rule (${resource.column()}, action) values (:resourceId, :action) returning id",
                ).param("resourceId", resource.id)
                .param("action", rule.action.name)
                .query(UUID::class.java)
                .single()
        rule.subjects.forEach { subject ->
            jdbc
                .sql(
                    """
                    insert into policy_subject (policy_rule_id, subject_type, external_id)
                    values (:ruleId, :type, :externalId)
                    """.trimIndent(),
                ).param("ruleId", ruleId)
                .param("type", subject.type())
                .param("externalId", subject.externalId())
                .update()
        }
    }

    /** Deletes the rule and, by cascade, its subjects. Returns whether there was a rule to delete. */
    fun delete(
        resource: ResourceRef,
        action: Action,
    ): Boolean =
        jdbc
            .sql("delete from policy_rule where ${resource.column()} = :resourceId and action = :action")
            .param("resourceId", resource.id)
            .param("action", action.name)
            .update() == 1

    private fun ResourceRef.column() =
        when (this) {
            is ResourceRef.NamespaceRef -> "namespace_id"
            is ResourceRef.ConfigSetRef -> "config_set_id"
        }

    private fun ResultSet.toResourceRef(): ResourceRef =
        getObject("namespace_id", UUID::class.java)?.let(ResourceRef::NamespaceRef)
            ?: ResourceRef.ConfigSetRef(getObject("config_set_id", UUID::class.java))

    private fun Subject.type() =
        when (this) {
            Subject.Public -> PUBLIC
            Subject.Authenticated -> AUTHENTICATED
            is Subject.User -> USER
            is Subject.Group -> GROUP
            is Subject.Application -> APPLICATION
        }

    private fun Subject.externalId() =
        when (this) {
            Subject.Public, Subject.Authenticated -> null
            is Subject.User -> id
            is Subject.Group -> id
            is Subject.Application -> id
        }

    private fun ResultSet.toSubject(): Subject {
        val externalId = getString("external_id")
        return when (val type = getString("subject_type")) {
            PUBLIC -> Subject.Public
            AUTHENTICATED -> Subject.Authenticated
            USER -> Subject.User(externalId)
            GROUP -> Subject.Group(externalId)
            APPLICATION -> Subject.Application(externalId)
            else -> error("Unknown subject type '$type'")
        }
    }

    private companion object {
        const val PUBLIC = "PUBLIC"
        const val AUTHENTICATED = "AUTHENTICATED"
        const val USER = "USER"
        const val GROUP = "GROUP"
        const val APPLICATION = "APPLICATION"
    }
}
