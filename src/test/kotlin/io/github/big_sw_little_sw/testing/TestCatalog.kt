package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SourceType
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceService
import io.github.big_sw_little_sw.folio.catalog.internal.Rule
import io.github.big_sw_little_sw.folio.catalog.internal.RuleService
import io.github.big_sw_little_sw.folio.catalog.internal.Subject
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.boot.test.context.TestComponent
import java.util.UUID

/**
 * Builds catalog trees for tests, as a super admin. Until the Git slice (design 20), it creates ConfigSets through
 * [ConfigSets.create] with [SourceType.GIT], as a Source adapter does. Test classes share the database, so each tree
 * starts with a top-level Namespace that has a unique slug.
 */
@TestComponent
class TestCatalog(
    private val namespaces: NamespaceService,
    private val configSets: ConfigSets,
    private val rules: RuleService,
) {
    fun namespace(parent: NamespaceId? = null, slug: String = uniqueSlug(), description: String? = null): NamespaceId =
        namespaces.create(SUPER_ADMIN, parent, Slug(slug), description).id

    fun configSet(namespace: NamespaceId, slug: String = uniqueSlug(), description: String? = null): ConfigSetId =
        configSets.create(SUPER_ADMIN, namespace, Slug(slug), description, SourceType.GIT).id

    fun rule(resource: ResourceId, action: Action, vararg subjects: String) {
        rules.put(SUPER_ADMIN, resource, Rule(action, subjects.map(Subject::parse).toSet()))
    }

    /** The current path of the Namespace. For a top-level Namespace, this is its slug. */
    fun pathOf(namespace: NamespaceId): String = namespaces
        .get(SUPER_ADMIN, namespace)
        .namespace.path
        .toString()

    companion object {
        /** A member of `folio-admins`, the super-admin group of the test configuration. */
        val SUPER_ADMIN = Caller.Authenticated("root", setOf("folio-admins"), null)

        fun uniqueSlug(prefix: String = "t"): String = "$prefix-${UUID.randomUUID().toString().take(8)}"
    }
}
