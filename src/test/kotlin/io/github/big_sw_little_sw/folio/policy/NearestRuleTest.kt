package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Nearest-rule resolution, design 9.3. */
class NearestRuleTest {
    private val engineering: ResourceRef = ResourceRef.NamespaceRef(UUID.randomUUID())
    private val ai: ResourceRef = ResourceRef.NamespaceRef(UUID.randomUUID())
    private val hive: ResourceRef = ResourceRef.NamespaceRef(UUID.randomUUID())
    private val path = listOf(engineering, ai, hive)

    private val alice = ApplicationPrincipal.Authenticated("alice", setOf("editors"), null)
    private val admins = setOf<Subject>(Subject.Group("admins"))

    @Test
    fun `a rule on the target itself decides`() {
        val rules = mapOf(hive to listOf(Subject.User("alice")))

        assertEquals(Decision.Granted(hive, Subject.User("alice")), decide(alice, path, rules, admins))
    }

    @Test
    fun `without a local rule the nearest ancestor's rule is inherited`() {
        val rules = mapOf(engineering to listOf(Subject.Group("editors")))

        assertEquals(Decision.Granted(engineering, Subject.Group("editors")), decide(alice, path, rules, admins))
    }

    @Test
    fun `a nearer rule that does not match denies even when a farther rule would grant`() {
        val rules =
            mapOf(
                engineering to listOf(Subject.Group("editors")),
                ai to listOf(Subject.Group("ai-editors")),
            )

        assertEquals(Decision.NotGranted(ai), decide(alice, path, rules, admins))
    }

    @Test
    fun `a ConfigSet's own rule is nearer than its namespaces' rules`() {
        val serviceA = ResourceRef.ConfigSetRef(UUID.randomUUID())
        val rules = mapOf(hive to listOf(Subject.Group("editors")), serviceA to listOf(Subject.User("bob")))

        assertEquals(Decision.NotGranted(serviceA), decide(alice, path + serviceA, rules, admins))
        assertEquals(Decision.Granted(hive, Subject.Group("editors")), decide(alice, path, rules, admins))
    }

    @Test
    fun `a rule on another resource with the same UUID does not apply`() {
        val sameId = ResourceRef.ConfigSetRef(hive.id)

        assertEquals(Decision.NoRule, decide(alice, listOf(sameId), mapOf(hive to listOf(Subject.Public)), admins))
    }

    @Test
    fun `without any rule on the path the default is deny`() {
        assertEquals(Decision.NoRule, decide(alice, path, emptyMap(), admins))
    }

    @Test
    fun `at the root only bootstrap admins are allowed`() {
        val admin = ApplicationPrincipal.Authenticated("bob", setOf("admins"), null)

        assertEquals(Decision.NoRule, decide(alice, emptyList(), emptyMap(), admins))
        assertEquals(Decision.BootstrapAdmin(Subject.Group("admins")), decide(admin, emptyList(), emptyMap(), admins))
    }

    @Test
    fun `bootstrap admins are allowed even where a nearer rule does not name them`() {
        val admin = ApplicationPrincipal.Authenticated("bob", setOf("admins"), null)
        val rules = mapOf(hive to listOf(Subject.User("alice")))

        assertEquals(Decision.BootstrapAdmin(Subject.Group("admins")), decide(admin, path, rules, admins))
    }

    @Test
    fun `an anonymous caller is allowed only by a public subject`() {
        val anonymous = ApplicationPrincipal.Anonymous

        assertEquals(
            Decision.NotGranted(hive),
            decide(anonymous, path, mapOf(hive to listOf(Subject.Authenticated)), admins),
        )
        assertEquals(
            Decision.Granted(hive, Subject.Public),
            decide(anonymous, path, mapOf(hive to listOf(Subject.Authenticated, Subject.Public)), admins),
        )
    }
}
