package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.folio.identity.Subject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** These tests check the nearest-rule algorithm (design 7.2) without Spring. */
class DecisionTest {
    private val engineering = NamespaceId(UUID.randomUUID())
    private val search = NamespaceId(UUID.randomUUID())
    private val serviceA = ConfigSetId(UUID.randomUUID())
    private val chain = listOf<ResourceId>(engineering, search, serviceA)

    private val alice = Caller.Authenticated("alice", setOf("editors"), null)
    private val admin = Caller.Authenticated("root", emptySet(), null, superAdmin = true)

    @Test
    fun `a rule on the resource itself decides`() {
        val rules = mapOf<ResourceId, Set<Subject>>(serviceA to setOf(Subject.User("alice")))

        assertEquals(Decision.Granted(serviceA, Subject.User("alice")), decide(alice, chain, rules))
    }

    @Test
    fun `without its own rule, a resource uses the rule of its nearest ancestor`() {
        val rules = mapOf<ResourceId, Set<Subject>>(engineering to setOf(Subject.Group("editors")))

        assertEquals(Decision.Granted(engineering, Subject.Group("editors")), decide(alice, chain, rules))
    }

    @Test
    fun `a nearer rule replaces the inherited rule, also when the inherited rule would permit`() {
        val rules =
            mapOf<ResourceId, Set<Subject>>(
                engineering to setOf(Subject.Group("editors")),
                search to setOf(Subject.Application("search-app")),
            )

        assertEquals(Decision.NotGranted(search), decide(alice, chain, rules))
    }

    @Test
    fun `without a rule on the chain, Folio denies`() {
        assertEquals(Decision.NoRule, decide(alice, chain, emptyMap()))
    }

    @Test
    fun `a rule on a resource outside the chain does not count`() {
        val other = NamespaceId(UUID.randomUUID())
        val rules = mapOf<ResourceId, Set<Subject>>(other to setOf(Subject.Public))

        assertEquals(Decision.NoRule, decide(alice, chain, rules))
    }

    @Test
    fun `a Namespace and a ConfigSet with the same UUID are different resources`() {
        val namespace = NamespaceId(serviceA.value)
        val rules = mapOf<ResourceId, Set<Subject>>(namespace to setOf(Subject.Public))

        assertEquals(Decision.NoRule, decide(alice, listOf(serviceA), rules))
    }

    @Test
    fun `a super admin is permitted first, also where a nearer rule does not name it`() {
        val rules = mapOf<ResourceId, Set<Subject>>(serviceA to setOf(Subject.User("alice")))

        assertEquals(Decision.SuperAdmin, decide(admin, chain, rules))
    }

    @Test
    fun `at the root, only super admins are permitted`() {
        assertEquals(Decision.NoRule, decide(alice, emptyList(), emptyMap()))
        assertEquals(Decision.SuperAdmin, decide(admin, emptyList(), emptyMap()))
    }

    @Test
    fun `public permits anonymous callers, and system work matches no rule`() {
        val rules = mapOf<ResourceId, Set<Subject>>(engineering to setOf(Subject.Public))

        assertEquals(Decision.Granted(engineering, Subject.Public), decide(Caller.Anonymous, chain, rules))
        assertEquals(Decision.NotGranted(engineering), decide(Caller.System, chain, rules))
    }

    @Test
    fun `the explanation names the first subject that matches`() {
        val rules =
            mapOf<ResourceId, Set<Subject>>(
                search to linkedSetOf(Subject.User("bob"), Subject.Group("editors"), Subject.Authenticated),
            )

        assertEquals(Decision.Granted(search, Subject.Group("editors")), decide(alice, chain, rules))
    }
}
