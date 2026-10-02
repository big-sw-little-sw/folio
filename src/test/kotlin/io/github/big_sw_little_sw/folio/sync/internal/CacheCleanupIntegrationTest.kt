package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.exampleSource
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import io.github.big_sw_little_sw.folio.source.internal.SourceProperties
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Removing cached repositories of deleted ConfigSets (ADR 0034). The repositories are stand-in directories. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CacheCleanupIntegrationTest(
    @Autowired private val cleanup: CacheCleanup,
    @Autowired private val configSets: ConfigSetService,
    @Autowired private val credentials: CredentialService,
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val meters: MeterRegistry,
    @Autowired properties: SourceProperties,
) {
    private val root: Path = properties.cacheDirectory

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
        authenticateAs(SUPER_ADMIN)
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `deleting a ConfigSet removes its cached repository after the commit`() {
        val configSet = configSet()
        val repository = repositoryOf(configSet)

        configSets.delete(configSet.id)

        assertFalse(repository.exists())
    }

    @Test
    fun `the sweep removes repositories of ConfigSets that no longer exist, nothing else, and measures the rest`() {
        val kept = repositoryOf(configSet())
        val orphan = root.resolve("${UUID.randomUUID()}.git").createDirectories()
        val others =
            listOf(
                root.resolve("not-a-config-set").createDirectories(),
                root.resolve("${UUID.randomUUID()}").createDirectories(),
                root.resolve("${UUID.randomUUID().toString().uppercase()}.git").createDirectories(),
                root.resolve("1-1-1-1-1.git").createDirectories(),
            )
        try {
            // Also removes repositories that other test contexts left in the shared cache directory. Test classes run
            // one after another, and each creates its ConfigSets afresh, so none of them is still using one.
            cleanup.sweep()

            assertFalse(orphan.exists())
            assertEquals(emptyList(), (others + listOf(kept)).filterNot { it.exists() })
            assertEquals(1.0, meters.get("folio.source.cache.repositories").gauge().value())
        } finally {
            others.forEach { it.toFile().deleteRecursively() }
        }
    }

    private fun configSet(): ConfigSet {
        val namespace = namespaces.create(null, Slug("ns-${System.nanoTime()}"))
        return configSets.create(namespace.id, Slug("app"), credentials.exampleSource())
    }

    /** A stand-in for the ConfigSet's cached repository, with a file in it. */
    private fun repositoryOf(configSet: ConfigSet): Path {
        val repository = root.resolve("${configSet.id.value}.git").createDirectories()
        repository.resolve("HEAD").writeText("ref: refs/heads/main\n")
        return repository
    }
}
