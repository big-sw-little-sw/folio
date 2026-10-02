package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
    }

    @Test
    fun `stores every subject type and reads it back`() {
        val namespace = insertNamespace("a")

        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, everySubjectType))

        assertEquals(listOf(Rule(Action.NAMESPACE_VIEW, everySubjectType)), repository.findByResource(namespace))
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
            repository.findByResource(namespace),
        )
    }

    @Test
    fun `delete removes the rule and its subjects`() {
        val namespace = insertNamespace("a")
        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, everySubjectType))

        repository.delete(namespace, Action.NAMESPACE_VIEW)

        assertEquals(emptyList(), repository.findByResource(namespace))
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
            mapOf<ResourceRef, List<Subject>>(
                a to listOf(Subject.Public),
                b to listOf(Subject.Group("editors"), Subject.User("alice")),
            ),
            repository.findSubjects(Action.NAMESPACE_VIEW, listOf(a, b)),
        )
        assertEquals(emptyMap(), repository.findSubjects(Action.NAMESPACE_VIEW, emptyList()))
    }

    @Test
    fun `deleting a namespace cascades to its rules and subjects`() {
        val namespace = insertNamespace("a")
        repository.put(namespace, Rule(Action.NAMESPACE_VIEW, everySubjectType))

        jdbc.sql("delete from namespace where id = :id").param("id", namespace.id).update()

        assertEquals(0, jdbc.sql("select count(*) from policy_rule").query(Int::class.java).single())
        assertEquals(0, jdbc.sql("select count(*) from policy_subject").query(Int::class.java).single())
    }

    @Test
    fun `finds ConfigSet rules alongside the rules of its namespaces`() {
        val namespace = insertNamespace("a")
        val configSet = insertConfigSet(namespace, "service-a")
        repository.put(namespace, Rule(Action.CONFIG_SET_VIEW, setOf(Subject.Public)))
        repository.put(configSet, Rule(Action.CONFIG_SET_VIEW, setOf(Subject.User("alice"))))

        assertEquals(
            mapOf<ResourceRef, List<Subject>>(
                namespace to listOf(Subject.Public),
                configSet to listOf(Subject.User("alice")),
            ),
            repository.findSubjects(Action.CONFIG_SET_VIEW, listOf(namespace, configSet)),
        )
        assertEquals(listOf(Rule(Action.CONFIG_SET_VIEW, setOf(Subject.Public))), repository.findByResource(namespace))
    }

    @Test
    fun `put and delete on a ConfigSet leave the namespace's rule for the same action alone`() {
        val namespace = insertNamespace("a")
        val configSet = insertConfigSet(namespace, "service-a")
        repository.put(namespace, Rule(Action.CONFIG_SET_VIEW, setOf(Subject.Public)))

        repository.put(configSet, Rule(Action.CONFIG_SET_VIEW, setOf(Subject.User("alice"))))
        repository.delete(configSet, Action.CONFIG_SET_VIEW)

        assertEquals(emptyList(), repository.findByResource(configSet))
        assertEquals(listOf(Rule(Action.CONFIG_SET_VIEW, setOf(Subject.Public))), repository.findByResource(namespace))
    }

    @Test
    fun `deleting a ConfigSet cascades to its rules and subjects`() {
        val configSet = insertConfigSet(insertNamespace("a"), "service-a")
        repository.put(configSet, Rule(Action.CONFIG_SET_VIEW, everySubjectType))

        jdbc.sql("delete from config_set where id = :id").param("id", configSet.id).update()

        assertEquals(0, jdbc.sql("select count(*) from policy_rule").query(Int::class.java).single())
        assertEquals(0, jdbc.sql("select count(*) from policy_subject").query(Int::class.java).single())
    }

    @Test
    fun `a rule belongs to exactly one namespace or ConfigSet`() {
        val namespace = insertNamespace("a")
        val configSet = insertConfigSet(namespace, "service-a")
        val insert = "insert into policy_rule (namespace_id, config_set_id, action) values (:ns, :cfg, 'POLICY_VIEW')"

        assertFailsWith<DataIntegrityViolationException> {
            jdbc
                .sql(insert)
                .param("ns", namespace.id)
                .param("cfg", configSet.id)
                .update()
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc
                .sql(insert)
                .param("ns", null)
                .param("cfg", null)
                .update()
        }
    }

    // Policy does not depend on the namespace or ConfigSet modules, so the test writes the rows directly.
    private fun insertNamespace(slug: String) =
        ResourceRef.NamespaceRef(
            jdbc
                .sql("insert into namespace (slug) values (:slug) returning id")
                .param("slug", slug)
                .query(UUID::class.java)
                .single(),
        )

    private fun insertConfigSet(
        namespace: ResourceRef.NamespaceRef,
        slug: String,
    ): ResourceRef.ConfigSetRef {
        // A source needs a credential row; policy never reads it, so it needs no key.
        val credentialId =
            jdbc
                .sql(
                    """
                    insert into credential (git_instance, name, status)
                    values ('example', 'test-' || gen_random_uuid(), 'ENABLED')
                    returning id
                    """.trimIndent(),
                ).query(UUID::class.java)
                .single()
        return ResourceRef.ConfigSetRef(
            jdbc
                .sql(
                    """
                    insert into config_set (namespace_id, slug, credential_id, repository_path, branch, root_path)
                    values (:namespaceId, :slug, :credentialId, 'org/repo.git', 'main', '')
                    returning id
                    """.trimIndent(),
                ).param("namespaceId", namespace.id)
                .param("slug", slug)
                .param("credentialId", credentialId)
                .query(UUID::class.java)
                .single(),
        )
    }
}
