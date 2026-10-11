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
import io.github.big_sw_little_sw.testing.send
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * These tests check the cache headers and the ETags of the consumption routes (design 12.1) with the in-memory Source.
 */
@FolioIntegrationTest
class ContentCachingIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val source: InMemorySource,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `an anonymous caller reads a public ConfigSet, and the responses are public`() {
        val id = catalog.configSet(catalog.namespace())
        catalog.rule(id, Action.READ, "public")
        val revision = source.publish(id, mapOf("app.yaml" to "a: 1\n"))

        mvc.get(url(id)).andExpect { header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=30") } }
        mvc.get("${url(id)}/files/app.yaml").andExpect {
            status { isOk() }
            header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=30") }
        }
        mvc.get("${url(id)}/files/app.yaml?revision=$revision").andExpect {
            header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, s-maxage=30, immutable") }
        }
        // A caller with a token gets the public policy too, because the policy depends on the rules of the ConfigSet.
        read("${url(id)}/files?revision=$revision").andExpect {
            header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, s-maxage=30, immutable") }
        }
    }

    @Test
    fun `a matching If-None-Match gives 304 with the cache headers and no body`() {
        val id = readable()
        val revision = source.publish(id, mapOf("app.yaml" to "a: 1\n"))

        ROUTES.forEach { route ->
            val first = read(url(id) + route).andReturn().response
            val etag = checkNotNull(first.getHeader(HttpHeaders.ETAG))

            read(url(id) + route, ifNoneMatch = etag).andExpect {
                status { isNotModified() }
                header { string(HttpHeaders.ETAG, etag) }
                header { string(HttpHeaders.CACHE_CONTROL, checkNotNull(first.getHeader(HttpHeaders.CACHE_CONTROL))) }
                content { string("") }
                // Only the file list route and the file route name one revision, so only they send the revision header
                // (design 12.1).
                if (route.startsWith("/files")) header { string(REVISION, revision.value) }
            }
            read(url(id) + route, ifNoneMatch = "\"other\"").andExpect { status { isOk() } }
        }
        // A 304 for a file reads no content, so it has no validation status.
        read("${url(id)}/files/app.yaml", ifNoneMatch = "W/\"x\", \"${TestItem.blobId("a: 1\n".toByteArray())}\"")
            .andExpect {
                status { isNotModified() }
                header { string(REVISION, revision.value) }
                header { doesNotExist(VALIDATION) }
            }
    }

    @Test
    fun `the ETags of the metadata and the revision list change with their content`() {
        val id = readable()
        catalog.rule(id, Action.MANAGE, "user:alice")
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        val metadataTag = etagOf(url(id))
        val revisionsTag = etagOf("${url(id)}/revisions")

        val revision = source.publish(id, mapOf("app.yaml" to "a: 2\n"))
        read(url(id), ifNoneMatch = metadataTag).andExpect {
            status { isOk() }
            jsonPath("$.latestRevision") { value(revision.value) }
        }
        read("${url(id)}/revisions", ifNoneMatch = revisionsTag).andExpect { status { isOk() } }
        assertNotEquals(revisionsTag, etagOf("${url(id)}/revisions"))

        val publishedTag = etagOf(url(id))
        mvc
            .send(HttpMethod.PATCH, "/api/v1/admin/configsets/${id.toApiId()}", alice, """{"description": "Changed"}""")
            .andExpect { status { isOk() } }
        read(url(id), ifNoneMatch = publishedTag).andExpect {
            status { isOk() }
            jsonPath("$.description") { value("Changed") }
        }
    }

    @Test
    fun `Folio authorizes before it examines If-None-Match`() {
        val id = catalog.configSet(catalog.namespace())
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        catalog.rule(id, Action.READ, "user:bob")
        val bob = Caller.Authenticated("bob", emptySet(), null)

        ROUTES.forEach { route ->
            val etag =
                checkNotNull(
                    mvc
                        .get(url(id) + route) { bearer(bob) }
                        .andReturn()
                        .response
                        .getHeader(HttpHeaders.ETAG),
                )
            read(url(id) + route, ifNoneMatch = etag).andExpectForbidden(Action.READ, id.toApiId())
            read(url(id) + route, ifNoneMatch = "*").andExpectForbidden(Action.READ, id.toApiId())
            mvc
                .get(url(id) + route) { header(HttpHeaders.IF_NONE_MATCH, etag) }
                .andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        }
    }

    /** Creates a ConfigSet that alice can READ. */
    private fun readable(): ConfigSetId {
        val id = catalog.configSet(catalog.namespace())
        catalog.rule(id, Action.READ, "user:alice")
        return id
    }

    private fun read(url: String, ifNoneMatch: String? = null): ResultActionsDsl = mvc.get(url) {
        bearer(alice)
        ifNoneMatch?.let { header(HttpHeaders.IF_NONE_MATCH, it) }
    }

    private fun url(id: ConfigSetId) = "/api/v1/configsets/${id.toApiId()}"

    private fun etagOf(url: String): String = checkNotNull(read(url).andReturn().response.getHeader(HttpHeaders.ETAG))

    private companion object {
        const val REVISION = "X-Folio-Revision"
        const val VALIDATION = "X-Folio-Validation-Status"

        /** The routes of the `content` module, relative to the URL of the ConfigSet. */
        val ROUTES = listOf("", "/revisions", "/files", "/files/app.yaml")
    }
}
