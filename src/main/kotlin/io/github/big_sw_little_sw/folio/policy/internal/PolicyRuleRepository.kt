package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** Rows of `policy_rule` and `policy_subject`. A rule always has at least one subject. */
@Repository
class PolicyRuleRepository(
    private val jdbc: JdbcClient,
) {
    /** Subjects of the rules for [action] on any of [namespaceIds], keyed by namespace. */
    fun findSubjects(
        action: Action,
        namespaceIds: List<UUID>,
    ): Map<UUID, List<Subject>> {
        if (namespaceIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                select r.namespace_id, s.subject_type, s.external_id
                from policy_rule r
                join policy_subject s on s.policy_rule_id = r.id
                where r.action = :action and r.namespace_id in (:namespaceIds)
                order by s.subject_type, s.external_id
                """.trimIndent(),
            ).param("action", action.name)
            .param("namespaceIds", namespaceIds)
            .query { rs, _ -> rs.getObject("namespace_id", UUID::class.java) to rs.toSubject() }
            .list()
            .groupBy({ it.first }, { it.second })
    }

    /** Rules on [namespaceId], ordered by action name. */
    fun findByNamespace(namespaceId: UUID): List<Rule> =
        jdbc
            .sql(
                """
                select r.action, s.subject_type, s.external_id
                from policy_rule r
                join policy_subject s on s.policy_rule_id = r.id
                where r.namespace_id = :namespaceId
                order by r.action, s.subject_type, s.external_id
                """.trimIndent(),
            ).param("namespaceId", namespaceId)
            .query { rs, _ -> Action.valueOf(rs.getString("action")) to rs.toSubject() }
            .list()
            .groupBy({ it.first }, { it.second })
            .map { (action, subjects) -> Rule(action, subjects.toSet()) }

    /** Replaces the rule for the same action on [namespaceId], if any. */
    fun put(
        namespaceId: UUID,
        rule: Rule,
    ) {
        delete(namespaceId, rule.action)
        val ruleId =
            jdbc
                .sql("insert into policy_rule (namespace_id, action) values (:namespaceId, :action) returning id")
                .param("namespaceId", namespaceId)
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

    /** Deletes the rule and, by cascade, its subjects. */
    fun delete(
        namespaceId: UUID,
        action: Action,
    ) {
        jdbc
            .sql("delete from policy_rule where namespace_id = :namespaceId and action = :action")
            .param("namespaceId", namespaceId)
            .param("action", action.name)
            .update()
    }

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
