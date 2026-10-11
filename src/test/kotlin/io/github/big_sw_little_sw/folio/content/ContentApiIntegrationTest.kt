package io.github.big_sw_little_sw.folio.content

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.InMemorySource
import io.github.big_sw_little_sw.testing.InMemorySource.TestItem
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.andExpectForbidden
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * These tests check the consumption routes of content (design 8.2, 9, 12.1) with the in-memory Source. The
 * validation of each format has its own unit test.
 */
@FolioIntegrationTest
class ContentApiIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val source: InMemorySource,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `metadata names the path, the description and the latest revision`() {
        val namespace = catalog.namespace()
        val id = catalog.configSet(namespace, "service-a", "The search service")
        catalog.rule(namespace, Action.READ, "user:alice")

        read(url(id)).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id.toApiId()) }
            jsonPath("$.path") { value("${catalog.pathOf(namespace)}/service-a") }
            jsonPath("$.description") { value("The search service") }
            jsonPath("$.latestRevision") { value(null) }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
            header { exists(HttpHeaders.ETAG) }
            header { string(CONTENT_TYPE_OPTIONS, "nosniff") }
        }
        val revision = source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        read(url(id)).andExpect { jsonPath("$.latestRevision") { value(revision.value) } }
    }

    @Test
    fun `each route finds the ConfigSet first, then authorizes READ`() {
        val id = catalog.configSet(catalog.namespace())
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        catalog.rule(id, Action.MANAGE, "user:alice")
        val missing = ConfigSetId(UUID.randomUUID())

        ROUTES.forEach { route ->
            read(url(missing) + route).andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
            read(url(id) + route).andExpectForbidden(Action.READ, id.toApiId())
            mvc.get(url(id) + route).andExpectProblem(401, "AUTHENTICATION_REQUIRED").andExpect {
                header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            }
        }
    }

    @Test
    fun `the revision list is newest first with a maximum of 100`() {
        val id = readable()
        val revisions = (1..101).map { source.publish(id, mapOf("n.txt" to "$it")) }

        read("${url(id)}/revisions").andExpect {
            status { isOk() }
            jsonPath("$.revisions") { value(hasSize<Any>(100)) }
            jsonPath("$.revisions[0].id") { value(revisions.last().value) }
            jsonPath("$.revisions[99].id") { value(revisions[1].value) }
            jsonPath("$.revisions[0].publishedAt") { exists() }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
        }
        // The oldest revision is not in the list, but it is still readable by its ID.
        read("${url(id)}/files/n.txt?revision=${revisions.first()}").andExpect { content { string("1") } }
    }

    @Test
    fun `the file list at latest names the exact revision`() {
        val id = readable()
        val revision = source.publish(id, mapOf("b/extra.json" to "{}", "app.yaml" to "a: 1\n"))

        read("${url(id)}/files").andExpect {
            status { isOk() }
            jsonPath("$.revision") { value(revision.value) }
            jsonPath("$.files[*].path") { value(contains("app.yaml", "b/extra.json")) }
            jsonPath("$.files[*].size") { value(contains(5, 2)) }
            header { string(REVISION, revision.value) }
            header { string(HttpHeaders.ETAG, "\"$revision\"") }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
        }
        read("${url(id)}/files?revision=$revision").andExpect {
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable") }
        }
        // An empty revision value selects the default, latest (design 12.1).
        read("${url(id)}/files?revision=").andExpect {
            status { isOk() }
            header { string(REVISION, revision.value) }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
        }
        read("${url(id)}/files/app.yaml?revision=").andExpect {
            status { isOk() }
            header { string(REVISION, revision.value) }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
        }
    }

    @Test
    fun `a file read gives the raw bytes with the media type, the revision and the validation status`() {
        val id = readable()
        val yaml = "server:\n  port: 8080\n"
        val revision = source.publish(id, mapOf("app.yaml" to yaml, "bad.json" to "{\"a\": 1,}", "notes.txt" to "x"))

        read("${url(id)}/files/app.yaml").andExpect {
            status { isOk() }
            content { string(yaml) }
            header { string(HttpHeaders.CONTENT_TYPE, "application/yaml") }
            header { string(HttpHeaders.CONTENT_LENGTH, yaml.length.toString()) }
            header { string(REVISION, revision.value) }
            header { string(VALIDATION, "VALID") }
            header { string(HttpHeaders.ETAG, "\"${TestItem.blobId(yaml.toByteArray())}\"") }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
            header { stringValues(CONTENT_TYPE_OPTIONS, "nosniff") }
        }
        // Folio serves a file that does not parse, and the validation header reports INVALID.
        read("${url(id)}/files/bad.json").andExpect {
            content { string("{\"a\": 1,}") }
            header { string(HttpHeaders.CONTENT_TYPE, "application/json") }
            header { string(VALIDATION, "INVALID") }
        }
        read("${url(id)}/files/notes.txt").andExpect {
            header { string(HttpHeaders.CONTENT_TYPE, "application/octet-stream") }
            header { string(VALIDATION, "UNKNOWN") }
        }
    }

    @Test
    fun `an exact revision keeps its content after a newer publish`() {
        val id = readable()
        val old = source.publish(id, mapOf("app.yaml" to "v: 1\n"))
        val new = source.publish(id, mapOf("app.yaml" to "v: 2\n"))

        read("${url(id)}/files/app.yaml?revision=$old").andExpect {
            content { string("v: 1\n") }
            header { string(REVISION, old.value) }
            header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable") }
        }
        read("${url(id)}/files/app.yaml?revision=latest").andExpect {
            content { string("v: 2\n") }
            header { string(REVISION, new.value) }
        }
    }

    @Test
    fun `latest of a ConfigSet without a revision is not published yet`() {
        val id = readable()

        read("${url(id)}/files").andExpectProblem(404, "NOT_PUBLISHED_YET")
        read("${url(id)}/files/app.yaml").andExpectProblem(404, "NOT_PUBLISHED_YET")
        read("${url(id)}/revisions").andExpect { jsonPath("$.revisions") { value(hasSize<Any>(0)) } }
    }

    @Test
    fun `a revision that the ConfigSet did not publish is not found`() {
        val id = readable()
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        val other = source.publish(catalog.configSet(catalog.namespace()), mapOf("app.yaml" to "a: 1\n"))

        read("${url(id)}/files?revision=$other").andExpectProblem(404, "REVISION_NOT_FOUND")
        read("${url(id)}/files/app.yaml?revision=$other").andExpectProblem(404, "REVISION_NOT_FOUND")
        read("${url(id)}/files/missing.yaml").andExpectProblem(404, "ITEM_NOT_FOUND")
    }

    @Test
    fun `a malformed revision gives 400 before Folio finds the ConfigSet`() {
        val missing = ConfigSetId(UUID.randomUUID())

        listOf("A".repeat(40), "a-b", "refs/heads/main").forEach { revision ->
            read("${url(missing)}/files?revision=$revision").andExpectProblem(400, "INVALID_REVISION")
            read("${url(missing)}/files/app.yaml?revision=$revision").andExpectProblem(400, "INVALID_REVISION")
        }
    }

    @Test
    fun `a path that is not in normal form gives 400, also in encoded forms`() {
        val id = readable()
        source.publish(id, mapOf("app.yaml" to "secret: 1\n", "a/app.yaml" to "secret: 2\n"))

        listOf(
            "../app.yaml",
            "a/../app.yaml",
            "..%2Fapp.yaml",
            "%2e%2e/app.yaml",
            "a/%2E%2E/app.yaml",
            "a%5Capp.yaml",
            "a%5C..%5Capp.yaml",
            "./app.yaml",
            "a//app.yaml",
            "a/",
            "app%00.yaml",
        ).forEach { path ->
            mvc.get(URI.create("${url(id)}/files/$path")) { bearer(alice) }.andExpect {
                status { isBadRequest() }
                content { string(not(containsString("secret"))) }
            }
        }
        read("${url(id)}/files/a/app.yaml").andExpect { content { string("secret: 2\n") } }
        // The Spring Security firewall accepts this path, so the content module must reject it. The content module
        // parses the path before it finds the ConfigSet, so an unknown ConfigSet ID also gives status 400.
        read("${url(id)}/files/a/").andExpectProblem(400, "INVALID_ITEM_PATH")
        read("${url(ConfigSetId(UUID.randomUUID()))}/files/a/").andExpectProblem(400, "INVALID_ITEM_PATH")
    }

    @Test
    fun `a file above 3 MB is streamed without validation`() {
        val id = readable()
        val large = "a: " + "x".repeat(3 * 1024 * 1024) + "\n"
        source.publish(id, mapOf("large.yaml" to large))

        val response =
            read("${url(id)}/files/large.yaml")
                .andExpect {
                    status { isOk() }
                    header { string(VALIDATION, "UNKNOWN") }
                    header { string(HttpHeaders.CONTENT_LENGTH, large.length.toString()) }
                }.andReturn()
                .response
        assertEquals(large, response.contentAsString)
    }

    @Test
    fun `a file of exactly 3 MB is in memory and validated`() {
        val id = readable()
        source.publish(id, mapOf("exact.json" to "[" + " ".repeat(3 * 1024 * 1024 - 2) + "]"))

        read("${url(id)}/files/exact.json").andExpect {
            status { isOk() }
            header { string(HttpHeaders.CONTENT_LENGTH, (3 * 1024 * 1024).toString()) }
            header { string(VALIDATION, "VALID") }
        }
    }

    @Test
    fun `a read that fails gives an error without the headers of the file`() {
        val id = readable()
        val size = 4L * 1024 * 1024
        // The large file is streamed, so its read fails only after the route set the headers of the file.
        val revision =
            source.publishItems(id, mapOf("large.bin" to failingItem(size), "small.yaml" to failingItem(10)))

        listOf("large.bin", "small.yaml").forEach { path ->
            val response =
                read("${url(id)}/files/$path?revision=$revision")
                    .andExpectProblem(502, "UNREACHABLE")
                    .andExpect {
                        header { doesNotExist(HttpHeaders.ETAG) }
                        header { doesNotExist(REVISION) }
                        header { doesNotExist(VALIDATION) }
                    }.andReturn()
                    .response
            assertNotEquals(size.toString(), response.getHeader(HttpHeaders.CONTENT_LENGTH))
        }
    }

    @Test
    fun `a file above the maximum file size gives 422 before the adapter opens it`() {
        val id = readable()
        source.publishItems(id, mapOf("huge.bin" to TestItem.unopened(100L * 1024 * 1024 + 1)))

        read("${url(id)}/files/huge.bin").andExpectProblem(422, "ITEM_TOO_LARGE")
        read("${url(id)}/files").andExpect { jsonPath("$.files[0].size") { value(100L * 1024 * 1024 + 1) } }
    }

    @Test
    fun `Source failures keep the code and the status of the adapter`() {
        val id = readable()
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        val revision = source.publish(id, mapOf("app.yaml" to "a: 2\n"))

        source.fail(id, ContentException.SourceBusy(Duration.ofSeconds(5)))
        read("${url(id)}/files/app.yaml").andExpectProblem(503, "SOURCE_BUSY").andExpect {
            header { string(HttpHeaders.RETRY_AFTER, "5") }
        }
        source.fail(id, ContentException.SourceFailed("UNREACHABLE", "Folio cannot connect to the Git server."))
        read("${url(id)}/files").andExpectProblem(502, "UNREACHABLE")
        source.fail(id, ContentException.SourceTimedOut("DEADLINE_EXCEEDED", "The fetch took too long."))
        read("${url(id)}/revisions").andExpectProblem(504, "DEADLINE_EXCEEDED")
        source.fail(id, ContentException.RevisionUnavailable(revision))
        read("${url(id)}/files/app.yaml?revision=$revision").andExpectProblem(404, "REVISION_UNAVAILABLE")
        source.recover(id)
        read("${url(id)}/files/app.yaml").andExpect { status { isOk() } }
    }

    /** Creates a ConfigSet that alice can READ. */
    private fun readable(): ConfigSetId {
        val id = catalog.configSet(catalog.namespace())
        catalog.rule(id, Action.READ, "user:alice")
        return id
    }

    private fun read(url: String): ResultActionsDsl = mvc.get(url) { bearer(alice) }

    private fun url(id: ConfigSetId) = "/api/v1/configsets/${id.toApiId()}"

    /** Returns a file whose stream fails at the first read. A Git adapter can fail in this way during a stream. */
    private fun failingItem(size: Long) = TestItem(size, "e") {
        object : InputStream() {
            override fun read(): Int =
                throw ContentException.SourceFailed("UNREACHABLE", "Folio cannot connect to the Git server.")
        }
    }

    private companion object {
        const val REVISION = "X-Folio-Revision"
        const val VALIDATION = "X-Folio-Validation-Status"
        const val CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

        /** The routes of the `content` module, relative to the URL of the ConfigSet. */
        val ROUTES = listOf("", "/revisions", "/files", "/files/app.yaml")
    }
}
