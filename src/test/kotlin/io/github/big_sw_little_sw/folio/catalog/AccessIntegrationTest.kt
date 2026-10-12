package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.*

/** Access and ConfigSets are the public interface that the catalog gives to other modules. */
@FolioIntegrationTest
class AccessIntegrationTest(
    @Autowired private val access: Access,
    @Autowired private val configSets: ConfigSets,
    @Autowired private val catalog: TestCatalog,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), "cli")
    private val bob = Caller.Authenticated("bob", emptySet(), null)

    @Test
    fun `requireAllowed throws Forbidden to an authenticated caller and Unauthenticated to an anonymous caller`() {
        val serviceA = catalog.configSet(catalog.namespace())
        catalog.rule(serviceA, Action.READ, "group:team-a")

        access.requireAllowed(alice, Action.READ, serviceA)
        val forbidden =
            assertFailsWith<CatalogException.Forbidden> { access.requireAllowed(bob, Action.READ, serviceA) }
        assertEquals("ACTION_NOT_ALLOWED", forbidden.code)
        assertEquals("The caller does not have READ on ${serviceA.toApiId()}.", forbidden.detail)
        assertFailsWith<CatalogException.Unauthenticated> {
            access.requireAllowed(Caller.Anonymous, Action.READ, serviceA)
        }
    }

    @Test
    fun `allows answers without an exception`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.MANAGE, "application:cli")

        assertTrue(access.allows(alice, Action.MANAGE, top))
        assertFalse(access.allows(alice, Action.READ, top), "MANAGE does not include READ")
        assertFalse(access.allows(bob, Action.MANAGE, top))
    }

    @Test
    fun `super admins can do all actions, also without rules`() {
        val serviceA = catalog.configSet(catalog.namespace())

        Action.entries.forEach { assertTrue(access.allows(SUPER_ADMIN, it, serviceA)) }
    }

    @Test
    fun `Access reads the super-admin status from the caller, not from its groups`() {
        val serviceA = catalog.configSet(catalog.namespace())
        val unflagged = Caller.Authenticated("root", setOf("folio-admins"), null)

        assertFalse(access.allows(unflagged, Action.READ, serviceA))
        assertTrue(access.allows(bob.copy(superAdmin = true), Action.READ, serviceA))
    }

    @Test
    fun `requireSuperAdmin permits only super admins, with the errors of the root`() {
        access.requireSuperAdmin(SUPER_ADMIN)

        val forbidden = assertFailsWith<CatalogException.Forbidden> { access.requireSuperAdmin(alice) }
        assertEquals("The caller does not have MANAGE on the root.", forbidden.detail)
        assertFailsWith<CatalogException.Forbidden> { access.requireSuperAdmin(Caller.System) }
        val unauthenticated =
            assertFailsWith<CatalogException.Unauthenticated> { access.requireSuperAdmin(Caller.Anonymous) }
        assertEquals("AUTHENTICATION_REQUIRED", unauthenticated.code)
    }

    @Test
    fun `system work matches no rule, also not public`() {
        val serviceA = catalog.configSet(catalog.namespace())
        catalog.rule(serviceA, Action.READ, "public")

        assertFalse(access.allows(Caller.System, Action.READ, serviceA))
        assertFailsWith<CatalogException.Forbidden> { access.requireAllowed(Caller.System, Action.READ, serviceA) }
    }

    @Test
    fun `isPublic is true when an anonymous caller can READ`() {
        val top = catalog.namespace()
        val open = catalog.configSet(top, "open")
        val closed = catalog.configSet(top, "closed")
        catalog.rule(top, Action.READ, "public")
        catalog.rule(closed, Action.READ, "authenticated")

        assertTrue(access.isPublic(open))
        assertFalse(access.isPublic(closed))
        assertTrue(access.allows(bob, Action.READ, closed))
    }

    @Test
    fun `a resource that does not exist is not permitted, except to super admins`() {
        val missing = ConfigSetId(UUID.randomUUID())

        assertFalse(access.allows(alice, Action.READ, missing))
        assertFailsWith<CatalogException.Forbidden> { access.requireAllowed(alice, Action.READ, missing) }
        assertTrue(access.allows(SUPER_ADMIN, Action.READ, missing))
    }

    @Test
    fun `ConfigSets create needs MANAGE on the Namespace, and find gives the path`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search")
        catalog.rule(search, Action.MANAGE, "user:alice")

        val created = configSets.create(alice, search, Slug("service-a"), " ", SourceType.GIT)
        assertNull(created.description, "A blank description is no description")
        assertEquals(created, configSets.find(created.id))
        assertEquals(
            "service-a",
            created.path.slugs
                .last()
                .value,
        )
        assertEquals(3, created.path.slugs.size)
        assertFailsWith<CatalogException.Forbidden> {
            configSets.create(bob, search, Slug("service-b"), null, SourceType.GIT)
        }
        assertFailsWith<CatalogException.SlugTaken> {
            configSets.create(alice, search, Slug("service-a"), null, SourceType.GIT)
        }
        assertFailsWith<CatalogException.NamespaceNotFound> {
            configSets.create(SUPER_ADMIN, NamespaceId(UUID.randomUUID()), Slug("x"), null, SourceType.GIT)
        }
        assertNull(configSets.find(ConfigSetId(UUID.randomUUID())))
    }
}
