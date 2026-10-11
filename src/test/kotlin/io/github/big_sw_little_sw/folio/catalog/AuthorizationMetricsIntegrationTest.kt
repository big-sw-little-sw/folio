package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.CounterGrowth
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.send
import io.github.big_sw_little_sw.testing.tagKeys
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests check the meter `folio.authorization.decisions` (design 15.1) through [Access] and the HTTP routes. The
 * meter counts each decision about the caller of a request, with the action, the result and the reason as tags. A
 * super admin gets the same number of decisions as other callers.
 */
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
        assertEquals(setOf("action", "result", "reason"), meters.tagKeys(DECISIONS))
    }

    /** The READ decision for the super admin, the caller of [ConfigSets.requireReadable], counts once. */
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
                HttpMethod.POST,
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

        get("/api/v1/admin/namespaces/${namespace.toApiId()}", alice)
        assertEquals(listOf(3.0, 1.0, 0.0), listOf(granted, notGranted, superAdmin).map { it.added() })

        get("/api/v1/admin/namespaces/${namespace.toApiId()}", SUPER_ADMIN)
        assertEquals(4.0, superAdmin.added())

        get("/api/v1/admin/configsets/${visible.toApiId()}", SUPER_ADMIN)
        assertEquals(4.0 + 3.0, superAdmin.added())
    }

    private fun get(url: String, caller: Caller.Authenticated) {
        mvc.get(url) { bearer(caller) }.andExpect { status { isOk() } }
    }

    private fun decisions(action: Action, result: String, reason: String) =
        CounterGrowth(meters, DECISIONS, "action", action.name, "result", result, "reason", reason)

    private companion object {
        const val DECISIONS = "folio.authorization.decisions"
    }
}
