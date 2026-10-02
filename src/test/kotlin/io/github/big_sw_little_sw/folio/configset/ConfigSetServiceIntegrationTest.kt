package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.credential.CredentialDisabledException
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.CredentialNotFoundException
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.Namespace
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotEmptyException
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotFoundException
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import io.github.big_sw_little_sw.folio.source.Branch
import io.github.big_sw_little_sw.folio.source.RepositoryPath
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import io.github.big_sw_little_sw.folio.source.SourcePath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ConfigSetServiceIntegrationTest(
    @Autowired private val service: ConfigSetService,
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val credentials: CredentialService,
    @Autowired private val jdbc: JdbcClient,
) {
    private lateinit var source: SourceDefinition

    // Authorization has its own tests; these run as a super admin, who may do everything.
    @BeforeEach
    fun authenticateAsSuperAdmin() {
        authenticateAs(SUPER_ADMIN)
        deleteAll()
        source = credentials.exampleSource()
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `creates, gets and lists ConfigSets of a namespace ordered by slug`() {
        val production = namespace(null, "production")
        val b = service.create(production.id, Slug("b"), source)
        val a = service.create(production.id, Slug("a"), source)

        assertEquals(ConfigSet(a.id, production.id, Slug("a"), source), service.get(a.id))
        assertEquals(listOf(a, b), service.list(production.id))
    }

    @Test
    fun `stores the source given at creation`() {
        val production = namespace(null, "production")
        val custom =
            SourceDefinition(
                source.credentialId,
                RepositoryPath("team/configs.git"),
                Branch("release/1"),
                SourcePath.ROOT,
            )

        val created = service.create(production.id, Slug("service-a"), custom)

        assertEquals(custom, created.source)
        assertEquals(custom, service.get(created.id).source)
        assertEquals(custom, service.rename(created.id, Slug("service-b")).source)
    }

    @Test
    fun `a source needs an existing, enabled credential`() {
        val production = namespace(null, "production")
        val missing = source.copy(credentialId = CredentialId(UUID.randomUUID()))
        credentials.disable(source.credentialId)

        assertFailsWith<CredentialNotFoundException> { service.create(production.id, Slug("a"), missing) }
        assertFailsWith<CredentialDisabledException> { service.create(production.id, Slug("b"), source) }
        assertEquals(emptyList(), service.list(production.id))
    }

    @Test
    fun `rejects a duplicate slug in the same namespace`() {
        val production = namespace(null, "production")
        service.create(production.id, Slug("service-a"), source)

        assertFailsWith<DuplicateConfigSetSlugException> { service.create(production.id, Slug("service-a"), source) }
    }

    @Test
    fun `allows the same slug in different namespaces and next to a namespace with that slug`() {
        val development = namespace(null, "development")
        val production = namespace(null, "production")
        namespace(production, "service-a")

        service.create(development.id, Slug("service-a"), source)
        service.create(production.id, Slug("service-a"), source)

        assertEquals(1, service.list(development.id).size)
        assertEquals(1, service.list(production.id).size)
    }

    @Test
    fun `rename keeps the ID and rejects a sibling's slug`() {
        val production = namespace(null, "production")
        val a = service.create(production.id, Slug("a"), source)
        service.create(production.id, Slug("b"), source)

        assertEquals(ConfigSet(a.id, production.id, Slug("c"), source), service.rename(a.id, Slug("c")))
        assertFailsWith<DuplicateConfigSetSlugException> { service.rename(a.id, Slug("b")) }
        assertEquals(Slug("c"), service.get(a.id).slug)
    }

    @Test
    fun `move keeps the ID and rejects a slug taken in the target namespace`() {
        val development = namespace(null, "development")
        val production = namespace(null, "production")
        val serviceA = service.create(development.id, Slug("service-a"), source)
        val other = service.create(development.id, Slug("other"), source)
        service.create(production.id, Slug("other"), source)

        assertEquals(
            ConfigSet(serviceA.id, production.id, Slug("service-a"), source),
            service.move(serviceA.id, production.id),
        )
        assertFailsWith<DuplicateConfigSetSlugException> { service.move(other.id, production.id) }
        assertEquals(development.id, service.get(other.id).namespaceId)
    }

    @Test
    fun `delete removes the ConfigSet`() {
        val production = namespace(null, "production")
        val serviceA = service.create(production.id, Slug("service-a"), source)

        service.delete(serviceA.id)

        assertFailsWith<ConfigSetNotFoundException> { service.get(serviceA.id) }
        assertEquals(emptyList(), service.list(production.id))
    }

    @Test
    fun `resolves a path whose last segment is the ConfigSet`() {
        val engineering = namespace(null, "engineering")
        val ai = namespace(engineering, "ai")
        val serviceA = service.create(ai.id, Slug("service-a"), source)
        // A namespace with the same slug does not make the path ambiguous.
        namespace(ai, "service-a")

        assertEquals(serviceA, service.resolve(ConfigSetPath.parse("engineering/ai/service-a")))
    }

    @Test
    fun `a path that leads nowhere is not found`() {
        val engineering = namespace(null, "engineering")
        namespace(engineering, "ai")
        service.create(engineering.id, Slug("service-a"), source)

        listOf("engineering/ai", "engineering/missing", "missing/service-a", "engineering/ai/service-a").forEach {
            assertFailsWith<ConfigSetPathNotFoundException> { service.resolve(ConfigSetPath.parse(it)) }
        }
    }

    @Test
    fun `the ID stays stable and the path follows renames and moves of the ConfigSet and its ancestors`() {
        val engineering = namespace(null, "engineering")
        val ai = namespace(engineering, "ai")
        val platform = namespace(null, "platform")
        val id = service.create(ai.id, Slug("service-a"), source).id

        service.rename(id, Slug("service-b"))
        namespaces.rename(ai.id, Slug("ml"))
        namespaces.move(ai.id, platform.id)
        namespaces.rename(platform.id, Slug("infra"))

        assertEquals(id, service.resolve(ConfigSetPath.parse("infra/ml/service-b")).id)
        assertFailsWith<ConfigSetPathNotFoundException> {
            service.resolve(ConfigSetPath.parse("engineering/ai/service-a"))
        }

        service.move(id, engineering.id)

        assertEquals(id, service.resolve(ConfigSetPath.parse("engineering/service-b")).id)
        assertEquals(id, service.get(id).id)
    }

    @Test
    fun `a namespace that holds a ConfigSet cannot be deleted until the ConfigSet is gone`() {
        val production = namespace(null, "production")
        val serviceA = service.create(production.id, Slug("service-a"), source)

        assertFailsWith<NamespaceNotEmptyException> { namespaces.delete(production.id) }
        assertEquals(serviceA, service.get(serviceA.id))

        service.move(serviceA.id, namespace(null, "other").id)
        namespaces.delete(production.id)
    }

    @Test
    fun `operations in a missing namespace fail as namespace not found`() {
        val missing = NamespaceId(UUID.randomUUID())
        val serviceA = service.create(namespace(null, "production").id, Slug("service-a"), source)

        assertFailsWith<NamespaceNotFoundException> { service.create(missing, Slug("a"), source) }
        assertFailsWith<NamespaceNotFoundException> { service.move(serviceA.id, missing) }
        assertFailsWith<NamespaceNotFoundException> { service.list(missing) }
    }

    @Test
    fun `operations on a missing ConfigSet fail as not found`() {
        val missing = ConfigSetId(UUID.randomUUID())
        val production = namespace(null, "production")

        assertFailsWith<ConfigSetNotFoundException> { service.get(missing) }
        assertFailsWith<ConfigSetNotFoundException> { service.rename(missing, Slug("a")) }
        assertFailsWith<ConfigSetNotFoundException> { service.move(missing, production.id) }
        assertFailsWith<ConfigSetNotFoundException> { service.delete(missing) }
    }

    @Test
    fun `a concurrent namespace delete and ConfigSet create fail only with domain errors`() {
        repeat(20) {
            deleteAll()
            val production = namespace(null, "production")
            val barrier = CyclicBarrier(2)

            val results =
                Executors.newFixedThreadPool(2).use { executor ->
                    listOf(
                        { service.create(production.id, Slug("service-a"), source) },
                        { namespaces.delete(production.id) },
                    ).map { operation ->
                        executor.submit<Result<Any>> {
                            authenticateAs(SUPER_ADMIN)
                            barrier.await()
                            runCatching { operation() }
                        }
                    }.map { it.get() }
                }

            // Whichever takes the tree lock first wins; the other fails cleanly.
            assertEquals(1, results.count { it.isSuccess })
            val failure = results.single { it.isFailure }.exceptionOrNull()
            assertTrue(failure is NamespaceNotEmptyException || failure is NamespaceNotFoundException, "$failure")
            if (results.first().isSuccess) assertIs<NamespaceNotEmptyException>(failure)
        }
    }

    private fun deleteAll() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
    }

    private fun namespace(
        parent: Namespace?,
        slug: String,
    ): Namespace = namespaces.create(parent?.id, Slug(slug))
}
