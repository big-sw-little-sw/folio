package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PolicyRuleRepositoryIntegrationTest(
    @Autowired private val repository: PolicyRuleRepository,
    @Autowired private val jdbc: JdbcClient,
) {
    private val everySubjectType =
        setOf(
            Subject.Public,
            Subject.Authenticated,
            Subject.User("alice"),
            Subject.Group("editors"),
            Subject.Application("ci-app"),
        )

    @BeforeEach
    fun deleteAllNamespaces() {
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
    }

    @Test
    fun `stores every subject type and reads it back`() {
        val namespace = insertNamespace("a")

        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, everySubjectType))

        assertEquals(listOf(Rule(Action.NAMESPACE_VIEW, everySubjectType)), repository.findByNamespace(namespace))
    }

    @Test
    fun `put replaces the rule for the same action and keeps other actions`() {
        val namespace = insertNamespace("a")
        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, setOf(Subject.Public)))
        repository.put(namespace, Rule(Action.NAMESPACE_DELETE, setOf(Subject.User("alice"))))

        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, setOf(Subject.Group("editors"))))

        assertEquals(
            listOf(
                Rule(Action.NAMESPACE_DELETE, setOf(Subject.User("alice"))),
                Rule(Action.NAMESPACE_VIEW, setOf(Subject.Group("editors"))),
            ),
            repository.findByNamespace(namespace),
        )
    }

    @Test
    fun `delete removes the rule and its subjects`() {
        val namespace = insertNamespace("a")
        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, everySubjectType))

        repository.delete(namespace, Action.NAMESPACE_VIEW)

        assertEquals(emptyList(), repository.findByNamespace(namespace))
        assertEquals(0, jdbc.sql("select count(*) from policy_subject").query(Int::class.java).single())
    }

    @Test
    fun `finds the subjects for one action on the given namespaces only`() {
        val a = insertNamespace("a")
        val b = insertNamespace("b")
        val c = insertNamespace("c")
        repository.put(a, Rule(Action.NAMESPACE_VIEW, setOf(Subject.Public)))
        repository.put(b, Rule(Action.NAMESPACE_VIEW, setOf(Subject.User("alice"), Subject.Group("editors"))))
        repository.put(b, Rule(Action.NAMESPACE_DELETE, setOf(Subject.User("bob"))))
        repository.put(c, Rule(Action.NAMESPACE_VIEW, setOf(Subject.Authenticated)))

        assertEquals(
            mapOf(a to listOf(Subject.Public), b to listOf(Subject.Group("editors"), Subject.User("alice"))),
            repository.findSubjects(Action.NAMESPACE_VIEW, listOf(a, b)),
        )
        assertEquals(emptyMap(), repository.findSubjects(Action.NAMESPACE_VIEW, emptyList()))
    }

    @Test
    fun `deleting a namespace cascades to its rules and subjects`() {
        val namespace = insertNamespace("a")
        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, everySubjectType))

        jdbc.sql("delete from namespace where id = :id").param("id", namespace).update()

        assertEquals(0, jdbc.sql("select count(*) from policy_rule").query(Int::class.java).single())
        assertEquals(0, jdbc.sql("select count(*) from policy_subject").query(Int::class.java).single())
    }

    // Policy does not depend on the namespace module, so the test writes the row directly.
    private fun insertNamespace(slug: String): UUID =
        jdbc
            .sql("insert into namespace (slug) values (:slug) returning id")
            .param("slug", slug)
            .query(UUID::class.java)
            .single()
}
