package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.NotAuthenticatedException
import io.github.big_sw_little_sw.folio.policy.PermissionDeniedException
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
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

/** Authorization of namespace operations in the application services; the HTTP API test covers the rest. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class NamespaceAuthorizationIntegrationTest(
    @Autowired private val service: NamespaceService,
    @Autowired private val policies: NamespacePolicyService,
    @Autowired private val jdbc: JdbcClient,
) {
    @BeforeEach
    fun deleteAllNamespaces() {
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `an anonymous caller is denied as not authenticated`() {
        val engineering = asAdmin { service.create(null, Slug("engineering")) }

        assertFailsWith<NotAuthenticatedException> { service.get(engineering.id) }
        assertFailsWith<NotAuthenticatedException> { service.create(null, Slug("other")) }
    }

    @Test
    fun `an anonymous caller may do what a public rule grants and nothing more`() {
        val engineering = asAdmin { createWithRule("engineering", Action.NAMESPACE_VIEW, Subject.Public) }

        assertEquals(engineering, service.get(engineering.id))
        assertFailsWith<NotAuthenticatedException> { service.rename(engineering.id, Slug("eng")) }
    }

    @Test
    fun `an authenticated caller without a grant is denied permission`() {
        val engineering = asAdmin { service.create(null, Slug("engineering")) }
        authenticateAs("alice")

        assertFailsWith<PermissionDeniedException> { service.get(engineering.id) }
        assertFailsWith<PermissionDeniedException> { service.delete(engineering.id) }
    }

    @Test
    fun `children lists only the namespaces the caller may view`() {
        val visible = asAdmin { createWithRule("visible", Action.NAMESPACE_VIEW, Subject.Authenticated) }
        asAdmin { service.create(null, Slug("hidden")) }
        authenticateAs("alice")

        assertEquals(listOf(visible), service.children(null))
    }

    @Test
    fun `a move needs move on the namespace and create on the new parent`() {
        val source = asAdmin { createWithRule("source", Action.NAMESPACE_MOVE, Subject.User("alice")) }
        val ai = asAdmin { service.create(source.id, Slug("ai")) }
        val target = asAdmin { service.create(null, Slug("target")) }
        authenticateAs("alice")

        assertFailsWith<PermissionDeniedException> { service.move(ai.id, target.id) }

        asAdmin { policies.putRule(target.id, Rule(Action.NAMESPACE_CREATE, setOf(Subject.User("alice")))) }
        authenticateAs("alice")
        assertEquals(target.id, service.move(ai.id, target.id).parentId)
    }

    @Test
    fun `create on the new parent without move on the namespace is denied`() {
        val ai = asAdmin { service.create(null, Slug("ai")) }
        val target = asAdmin { createWithRule("target", Action.NAMESPACE_CREATE, Subject.User("alice")) }
        authenticateAs("alice")

        assertFailsWith<PermissionDeniedException> { service.move(ai.id, target.id) }
    }

    @Test
    fun `only bootstrap admins create at or move to the root`() {
        val engineering = asAdmin { createWithRule("engineering", Action.NAMESPACE_MOVE, Subject.User("alice")) }
        val ai = asAdmin { service.create(engineering.id, Slug("ai")) }
        authenticateAs("alice")

        assertFailsWith<PermissionDeniedException> { service.create(null, Slug("other")) }
        assertFailsWith<PermissionDeniedException> { service.move(ai.id, null) }
    }

    @Test
    fun `deleting a namespace deletes its rules`() {
        val engineering = asAdmin { createWithRule("engineering", Action.NAMESPACE_VIEW, Subject.Public) }

        asAdmin { service.delete(engineering.id) }

        assertEquals(0, jdbc.sql("select count(*) from policy_rule").query(Int::class.java).single())
    }

    private fun createWithRule(
        slug: String,
        action: Action,
        subject: Subject,
    ): Namespace {
        val namespace = service.create(null, Slug(slug))
        policies.putRule(namespace.id, Rule(action, setOf(subject)))
        return namespace
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
