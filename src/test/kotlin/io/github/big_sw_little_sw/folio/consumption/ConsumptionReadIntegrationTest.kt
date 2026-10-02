package io.github.big_sw_little_sw.folio.consumption

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.toApiId
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.sync.SyncFixture
import io.github.big_sw_little_sw.folio.sync.internal.Synchronizer
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectInserter
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import kotlin.test.Test

/** Metadata, listings and raw reads through the consumption API, with their caching headers (design 16). */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsumptionReadIntegrationTest(
    @Autowired mvc: MockMvc,
    @Autowired synchronizer: Synchronizer,
    @Autowired configSets: ConfigSetService,
    @Autowired credentials: CredentialService,
    @Autowired namespaces: NamespaceService,
    @Autowired jdbc: JdbcClient,
) {
    private val fixture =
        ConsumptionFixture(mvc, synchronizer, SyncFixture(configSets, credentials, namespaces, jdbc, REPOSITORY))

    @BeforeEach
    fun setUp() {
        fixture.reset()
    }

    @Test
    fun `metadata names the path and the latest synced revision, which is null before the first sync`() {
        val url = fixture.url
        val path = "production/${fixture.configSet.slug.value}"

        fixture.get(url).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(fixture.configSet.id.toApiId()) }
            jsonPath("$.path") { value(path) }
            jsonPath("$.latestRevision") { value(null) }
        }
        fixture.sync()
        fixture.get(url).andExpect {
            status { isOk() }
            jsonPath("$.latestRevision") { value(fixture.commits.last()) }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
            header { exists(HttpHeaders.ETAG) }
        }
    }

    @Test
    fun `the listing at latest has the regular files beneath the root path with their sizes`() {
        fixture.sync()
        val latest = fixture.commits.last()

        fixture.get("${fixture.url}/files").andExpect {
            status { isOk() }
            header { string(REVISION, latest) }
            header { string(HttpHeaders.ETAG, "\"$latest\"") }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
            jsonPath("$.revision") { value(latest) }
            // The symlink config/link is not a file; README.md is outside the root path.
            jsonPath("$.files[*].path") { value(contains("app.yaml", "sub/extra.json")) }
            jsonPath("$.files[*].size") { value(contains(2, 2)) }
        }
    }

    @Test
    fun `a raw read at latest serves the bytes with revision, validation, entity tag and short caching`() {
        fixture.sync()

        fixture.get("${fixture.url}/files/app.yaml").andExpect {
            status { isOk() }
            content { bytes("v2".toByteArray()) }
            content { contentType("application/yaml") }
            header { string(REVISION, fixture.commits.last()) }
            header { string(VALIDATION, "VALID") }
            header { string(HttpHeaders.ETAG, "\"${blobId("v2")}\"") }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
            header { string("X-Content-Type-Options", "nosniff") }
        }
        fixture.get("${fixture.url}/files/sub/extra.json").andExpect {
            status { isOk() }
            content { bytes("{}".toByteArray()) }
            content { contentType("application/json") }
            header { string(VALIDATION, "VALID") }
        }
    }

    @Test
    fun `an exact synced revision is served and listed with immutable caching`() {
        fixture.sync()
        val older = fixture.commits.last()
        val newer = SshGitServer.addCommit(REPOSITORY, "v3")
        fixture.sync()

        fixture.get("${fixture.url}/files/app.yaml?revision=$older").andExpect {
            status { isOk() }
            content { string("v2") }
            header { string(REVISION, older) }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable") }
        }
        fixture.get("${fixture.url}/files?revision=$older").andExpect {
            status { isOk() }
            jsonPath("$.revision") { value(older) }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable") }
        }
        fixture.get("${fixture.url}/files/app.yaml?revision=latest").andExpect {
            content { string("v3") }
            header { string(REVISION, newer) }
        }
    }

    @Test
    fun `a matching If-None-Match gives 304 with the same caching headers and no body`() {
        fixture.sync()

        listOf("", "/files", "/files/app.yaml", "/revisions").forEach { route ->
            val url = fixture.url + route
            val first = fixture.get(url).andReturn().response
            val etag = checkNotNull(first.getHeader(HttpHeaders.ETAG))

            fixture.get(url, ifNoneMatch = etag).andExpect {
                status { isNotModified() }
                header { string(HttpHeaders.ETAG, etag) }
                header { string(HttpHeaders.CACHE_CONTROL, checkNotNull(first.getHeader(HttpHeaders.CACHE_CONTROL))) }
                content { string("") }
            }
            fixture.get(url, ifNoneMatch = "\"other\"").andExpect { status { isOk() } }
        }
        fixture.get("${fixture.url}/files/app.yaml", ifNoneMatch = "\"${blobId("v2")}\"").andExpect {
            header { string(REVISION, fixture.commits.last()) }
        }
    }

    @Test
    fun `malformed files are served raw and marked invalid, unknown formats as octet streams`() {
        SshGitServer.addCommit(REPOSITORY, "a: [1, 2\\n  b: c")
        SshGitServer.addCommit(REPOSITORY, "plain notes", "config/notes.txt")
        fixture.sync()

        fixture.get("${fixture.url}/files/app.yaml").andExpect {
            status { isOk() }
            content { string("a: [1, 2\n  b: c") }
            content { contentType("application/yaml") }
            header { string(VALIDATION, "INVALID") }
        }
        fixture.get("${fixture.url}/files/notes.txt").andExpect {
            status { isOk() }
            content { string("plain notes") }
            content { contentType("application/octet-stream") }
            header { string(VALIDATION, "UNKNOWN") }
        }
    }

    @Test
    fun `paths that are not normal relative paths are rejected, percent-encoded ones included`() {
        fixture.sync()

        listOf(
            "../README.md",
            "..%2FREADME.md",
            "%2e%2e/README.md",
            "sub/%2E%2E/%2E%2E/README.md",
            "sub%5C..%5C..%5CREADME.md",
            "/README.md",
            "sub/",
            "a%00b",
        ).forEach {
            fixture.get("${fixture.url}/files/$it").andExpect {
                status { isBadRequest() }
                content { string(not(containsString("readme"))) }
            }
        }
    }

    @Test
    fun `directories, symlinks and missing files are not found`() {
        fixture.sync()

        listOf("", "sub", "link", "missing.yaml", "sub/missing.json").forEach {
            fixture.get("${fixture.url}/files/$it").andExpect { status { isNotFound() } }
        }
    }

    private fun blobId(content: String) =
        ObjectInserter.Formatter().idFor(Constants.OBJ_BLOB, content.toByteArray()).name

    companion object {
        private const val REPOSITORY = "consumption-read"
        private const val REVISION = "X-Config-Revision"
        private const val VALIDATION = "X-Config-Validation-Status"

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
