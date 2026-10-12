package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.*
import kotlin.test.*

/** These tests check the meter `folio.authorization.decisions` through [Access] and the HTTP routes. */
@FolioIntegrationTest
class AuthorizationMetricsIntegrationTest(
    @Autowired private val access: Access,
    @Autowired private val configSets: ConfigSets,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val mvc: MockMvc,
) {
    private val alice = Caller.Authenticated("alice", emptySet(), null)
    private val bob = Caller.Authenticated("bob", emptySet(), null)

    @Test
    fun `each decision counts with its action, result and reason`() {
        val namespace = catalog.namespace()
        val configSet = catalog.configSet(namespace)
        val noRule = decisions(Action.MANAGE, "denied", "no_rule")
        val superAdmin = decisions(Action.MANAGE, "allowed", "super_admin")
        val granted = decisions(Action.READ, "allowed", "rule")
        val notGranted = decisions(Action.READ, "denied", "not_granted")

        assertFalse(access.allows(alice, Action.MANAGE, configSet))
        assertTrue(access.allows(SUPER_ADMIN, Action.MANAGE, configSet))
        catalog.rule(namespace, Action.READ, "user:alice")
        access.requireAllowed(alice, Action.READ, configSet)
        assertFalse(access.allows(bob, Action.READ, configSet))

        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), listOf(noRule, superAdmin, granted, notGranted).map { it.added() })
        val tagKeys = meters.find(DECISIONS).meters().flatMap { it.id.tags }.map { it.key }.toSet()
        assertEquals(setOf("action", "result", "reason"), tagKeys)
    }

    /**
     * The test calls [ConfigSets.requireReadable] as the super admin, so one READ decision about the super admin
     * counts. The explain route also counts its GRANT check of the super admin. The explained READ decision about alice
     * does not count.
     */
    @Test
    fun `the public check and the decision of an explanation do not count`() {
        val configSet = catalog.configSet(catalog.namespace())
        val callerRead = decisions(Action.READ, "allowed", "super_admin")
        val otherReads =
            listOf("allowed", "denied").flatMap { result ->
                listOf("super_admin", "rule", "not_granted", "no_rule")
                    .filterNot { result == "allowed" && it == "super_admin" }
                    .map { decisions(Action.READ, result, it) }
            }
        val grant = decisions(Action.GRANT, "allowed", "super_admin")

        assertFalse(configSets.requireReadable(SUPER_ADMIN, configSet).public)
        mvc
            .send(
                POST,
                "/api/v1/admin/configsets/${configSet.toApiId()}/explain",
                SUPER_ADMIN,
                """{"action": "READ", "caller": {"subject": "alice"}}""",
            ).andExpect { status { isOk() } }

        assertEquals(1.0, callerRead.added())
        assertEquals(0.0, otherReads.sumOf { it.added() })
        assertEquals(1.0, grant.added())
    }

    /**
     * A GET of a Namespace decides READ on the Namespace, on each child and on each link of the breadcrumbs. A nearer
     * rule on one child hides that child from alice.
     */
    @Test
    fun `each item of a list and each link of the breadcrumbs counts, also for a super admin`() {
        val namespace = catalog.namespace()
        val visible = catalog.configSet(namespace)
        val hidden = catalog.configSet(namespace)
        catalog.rule(namespace, Action.READ, "user:alice")
        catalog.rule(hidden, Action.READ, "user:bob")
        val granted = decisions(Action.READ, "allowed", "rule")
        val notGranted = decisions(Action.READ, "denied", "not_granted")
        val superAdmin = decisions(Action.READ, "allowed", "super_admin")

        mvc.send(GET, "/api/v1/admin/namespaces/${namespace.toApiId()}", alice).andExpect { status { isOk() } }
        assertEquals(listOf(3.0, 1.0, 0.0), listOf(granted, notGranted, superAdmin).map { it.added() })

        mvc.send(GET, "/api/v1/admin/namespaces/${namespace.toApiId()}", SUPER_ADMIN).andExpect { status { isOk() } }
        assertEquals(4.0, superAdmin.added())

        mvc.send(GET, "/api/v1/admin/configsets/${visible.toApiId()}", SUPER_ADMIN).andExpect { status { isOk() } }
        assertEquals(4.0 + 3.0, superAdmin.added())
    }

    private fun decisions(action: Action, result: String, reason: String) =
        CounterGrowth(meters, DECISIONS, "action", action.name, "result", result, "reason", reason)

    private companion object {
        const val DECISIONS = "folio.authorization.decisions"
    }
}
