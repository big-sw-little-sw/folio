package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.namespace.Namespace
import io.github.big_sw_little_sw.folio.namespace.NamespacePolicyService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Decision
import io.github.big_sw_little_sw.folio.policy.NotAuthenticatedException
import io.github.big_sw_little_sw.folio.policy.PermissionDeniedException
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import io.github.big_sw_little_sw.folio.security.BOOTSTRAP_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Authorization of ConfigSet operations in the application services; the HTTP API test covers the rest. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ConfigSetAuthorizationIntegrationTest(
    @Autowired private val service: ConfigSetService,
    @Autowired private val policies: ConfigSetPolicyService,
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val namespacePolicies: NamespacePolicyService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val alice = Subject.User("alice")

    @BeforeEach
    fun deleteAll() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `an anonymous caller is denied as not authenticated`() {
        val production = asAdmin { namespace("production") }
        val serviceA = asAdmin { service.create(production.id, Slug("service-a")) }

        assertFailsWith<NotAuthenticatedException> { service.get(serviceA.id) }
        assertFailsWith<NotAuthenticatedException> { service.create(production.id, Slug("other")) }
    }

    @Test
    fun `create needs create on the namespace`() {
        val production = asAdmin { namespace("production") }
        authenticateAs("alice")
        assertFailsWith<PermissionDeniedException> { service.create(production.id, Slug("service-a")) }

        asAdmin { grantOnNamespace(production, Action.CONFIG_SET_CREATE) }
        authenticateAs("alice")
        assertEquals(Slug("service-a"), service.create(production.id, Slug("service-a")).slug)
    }

    @Test
    fun `view, rename and delete need the matching action on the ConfigSet`() {
        val serviceA = asAdmin { service.create(namespace("production").id, Slug("service-a")) }
        authenticateAs("alice")
        assertFailsWith<PermissionDeniedException> { service.get(serviceA.id) }
        assertFailsWith<PermissionDeniedException> { service.rename(serviceA.id, Slug("b")) }
        assertFailsWith<PermissionDeniedException> { service.delete(serviceA.id) }

        asAdmin {
            listOf(Action.CONFIG_SET_VIEW, Action.CONFIG_SET_RENAME, Action.CONFIG_SET_DELETE).forEach {
                grantOnConfigSet(serviceA, it)
            }
        }
        authenticateAs("alice")
        assertEquals(serviceA, service.get(serviceA.id))
        assertEquals(Slug("b"), service.rename(serviceA.id, Slug("b")).slug)
        service.delete(serviceA.id)
    }

    @Test
    fun `a move needs move on the ConfigSet and create on the target namespace`() {
        val source = asAdmin { namespace("source") }
        val target = asAdmin { namespace("target") }
        val serviceA = asAdmin { service.create(source.id, Slug("service-a")) }
        asAdmin { grantOnConfigSet(serviceA, Action.CONFIG_SET_MOVE) }
        authenticateAs("alice")
        assertFailsWith<PermissionDeniedException> { service.move(serviceA.id, target.id) }

        asAdmin { grantOnNamespace(target, Action.CONFIG_SET_CREATE) }
        authenticateAs("alice")
        assertEquals(target.id, service.move(serviceA.id, target.id).namespaceId)
    }

    @Test
    fun `create on the target namespace without move on the ConfigSet is denied`() {
        val target = asAdmin { namespace("target") }
        val serviceA = asAdmin { service.create(namespace("source").id, Slug("service-a")) }
        asAdmin { grantOnNamespace(target, Action.CONFIG_SET_CREATE) }
        authenticateAs("alice")

        assertFailsWith<PermissionDeniedException> { service.move(serviceA.id, target.id) }
    }

    @Test
    fun `list and resolve show only ConfigSets the caller may view`() {
        val production = asAdmin { namespace("production") }
        val visible = asAdmin { service.create(production.id, Slug("visible")) }
        asAdmin { service.create(production.id, Slug("hidden")) }
        asAdmin { grantOnConfigSet(visible, Action.CONFIG_SET_VIEW) }
        authenticateAs("alice")

        assertEquals(listOf(visible), service.list(production.id))
        assertEquals(visible, service.resolve(ConfigSetPath.parse("production/visible")))
        assertFailsWith<PermissionDeniedException> { service.resolve(ConfigSetPath.parse("production/hidden")) }
    }

    @Test
    fun `a ConfigSet's own rule is nearer than its namespace's rule`() {
        val production = asAdmin { namespace("production") }
        val serviceA = asAdmin { service.create(production.id, Slug("service-a")) }
        val serviceB = asAdmin { service.create(production.id, Slug("service-b")) }
        asAdmin {
            grantOnNamespace(production, Action.CONFIG_SET_VIEW)
            policies.putRule(serviceA.id, Rule(Action.CONFIG_SET_VIEW, setOf(Subject.User("bob"))))
        }
        authenticateAs("alice")

        assertFailsWith<PermissionDeniedException> { service.get(serviceA.id) }
        assertEquals(serviceB, service.get(serviceB.id))
        authenticateAs("bob")
        assertEquals(serviceA, service.get(serviceA.id))
    }

    @Test
    fun `explain names the ConfigSet or the namespace whose rule decided`() {
        val production = asAdmin { namespace("production") }
        val serviceA = asAdmin { service.create(production.id, Slug("service-a")) }
        val principal = ApplicationPrincipal.Authenticated("alice", emptySet(), null)

        val actions = listOf(Action.CONFIG_SET_VIEW, Action.CONFIG_SET_DELETE)

        val decisions =
            asAdmin {
                grantOnNamespace(production, Action.CONFIG_SET_VIEW)
                grantOnConfigSet(serviceA, Action.CONFIG_SET_DELETE)
                actions.map { policies.explain(serviceA.id, principal, it) }
            }

        assertEquals(
            listOf(
                Decision.Granted(ResourceRef.NamespaceRef(production.id.value), alice),
                Decision.Granted(ResourceRef.ConfigSetRef(serviceA.id.value), alice),
            ),
            decisions,
        )
    }

    @Test
    fun `managing ConfigSet rules needs policy permissions on the ConfigSet`() {
        val serviceA = asAdmin { service.create(namespace("production").id, Slug("service-a")) }
        val rule = Rule(Action.CONFIG_SET_VIEW, setOf(alice))
        authenticateAs("alice")
        assertFailsWith<PermissionDeniedException> { policies.rules(serviceA.id) }
        assertFailsWith<PermissionDeniedException> { policies.putRule(serviceA.id, rule) }

        asAdmin { grantOnConfigSet(serviceA, Action.POLICY_UPDATE) }
        authenticateAs("alice")
        policies.putRule(serviceA.id, rule)
        assertFailsWith<PermissionDeniedException> { policies.rules(serviceA.id) }
    }

    @Test
    fun `deleting a ConfigSet deletes its rules`() {
        val serviceA = asAdmin { service.create(namespace("production").id, Slug("service-a")) }
        asAdmin { grantOnConfigSet(serviceA, Action.CONFIG_SET_VIEW) }

        asAdmin { service.delete(serviceA.id) }

        assertEquals(0, jdbc.sql("select count(*) from policy_rule").query(Int::class.java).single())
    }

    private fun namespace(slug: String): Namespace = namespaces.create(null, Slug(slug))

    private fun grantOnNamespace(
        namespace: Namespace,
        action: Action,
    ) {
        namespacePolicies.putRule(namespace.id, Rule(action, setOf(alice)))
    }

    private fun grantOnConfigSet(
        configSet: ConfigSet,
        action: Action,
    ) {
        policies.putRule(configSet.id, Rule(action, setOf(alice)))
    }

    private fun <T> asAdmin(block: () -> T): T {
        authenticateAs(BOOTSTRAP_ADMIN)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
