package io.github.big_sw_little_sw.folio.content.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.InMemorySource.TestItem
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.*
import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.*

@FolioIntegrationTest
class ContentPagesIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val source: InMemorySource,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `the ConfigSet page of the catalog links to the files and the revisions`() {
        val id = catalog.readableConfigSet("user:alice")

        val html = mvc.page(page(id), alice)

        assertContains(html, """<a href="${page(id)}/files">Files at the latest revision</a>""")
        assertContains(html, """<a href="${page(id)}/revisions">Revisions</a>""")
    }

    @Test
    fun `the revisions page lists the revisions newest first and marks latest`() {
        val id = catalog.readableConfigSet("user:alice")
        val old = source.publish(id, mapOf("app.yaml" to "v: 1\n"))
        val new = source.publish(id, mapOf("app.yaml" to "v: 2\n"))

        val html = mvc.page("${page(id)}/revisions", alice)

        val newLink = """<a href="${page(id)}/files?revision=$new"><code>$new</code></a>"""
        val oldLink = """<a href="${page(id)}/files?revision=$old"><code>$old</code></a>"""
        assertTrue(html.indexOf(newLink) in 0 until html.indexOf(oldLink), html)
        assertContains(html, """<span class="muted">latest</span>""")
    }

    @Test
    fun `the files page of latest names the exact revision and links to it`() {
        val id = catalog.readableConfigSet("user:alice")
        val revision = source.publish(id, mapOf("app.yaml" to "a: 1\n", "my dir/a#b.json" to "{}"))
        val encodedFile = "${page(id)}/files/my%20dir/a%23b.json?revision=$revision"

        val html = mvc.page("${page(id)}/files", alice)

        assertContains(html, """<code>$revision</code> <span class="muted">(latest)</span>""")
        assertContains(html, """<a href="${page(id)}/files/app.yaml?revision=$revision">app.yaml</a>""")
        assertContains(html, """<a href="$encodedFile">my dir/a#b.json</a>""")
        // A URI keeps the encoded segments. A URL template would encode the percent signs again.
        mvc.get(URI.create(encodedFile)) { with(signedIn(alice)) }.andExpect {
            status { isOk() }
            content { string(containsString("""<span class="validation">VALID</span>""")) }
        }
    }

    @Test
    fun `the file page shows the content as escaped text with the validation status`() {
        val id = catalog.readableConfigSet("user:alice")
        val revision = source.publish(id, mapOf("bad.yaml" to "a: [1\n<script>alert(1)</script>\n"))

        val html = mvc.page("${page(id)}/files/bad.yaml?revision=$revision", alice)

        assertContains(html, """<span class="validation">INVALID</span>""")
        assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt;")
        assertFalse("<script>alert" in html)
        assertContains(html, """<a href="${page(id)}/download/bad.yaml?revision=$revision">""")
    }

    @Test
    fun `the file page shows no text for a file above 3 MB`() {
        val id = catalog.readableConfigSet("user:alice")
        source.publish(id, mapOf("large.json" to "[" + " ".repeat(3 * 1024 * 1024) + "]"))

        val html = mvc.page("${page(id)}/files/large.json", alice)

        assertContains(html, """<span class="validation">UNKNOWN</span>""")
        assertContains(html, "The file is larger than 3 MB.")
    }

    @Test
    fun `a download is an attachment with nosniff and the raw bytes`() {
        val id = catalog.readableConfigSet("user:alice")
        val revision = source.publish(id, mapOf("site/index.html" to "<html><script>alert(1)</script></html>"))

        mvc.get("${page(id)}/download/site/index.html?revision=$revision") { with(signedIn(alice)) }.andExpect {
            status { isOk() }
            content { string("<html><script>alert(1)</script></html>") }
            header { string(HttpHeaders.CONTENT_TYPE, "application/octet-stream") }
            header {
                string(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"index.html\"; filename*=UTF-8''index.html",
                )
            }
            header { stringValues("X-Content-Type-Options", "nosniff") }
        }
    }

    @Test
    fun `the pages need READ`() {
        val id = catalog.configSet(catalog.namespace())
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        catalog.rule(id, Action.MANAGE, "user:alice")

        ROUTES.forEach { route ->
            mvc.get(page(id) + route) { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        }
    }

    @Test
    fun `an anonymous caller sees the pages of a public ConfigSet`() {
        val id = catalog.configSet(catalog.namespace())
        catalog.rule(id, Action.READ, "public")
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))

        ROUTES.forEach { route -> mvc.page(page(id) + route, null) }
    }

    @Test
    fun `the errors of content show on the error page`() {
        val id = catalog.readableConfigSet("user:alice")

        mvc.get("${page(id)}/files") { with(signedIn(alice)) }.andExpectProblemPage(404, "NOT_PUBLISHED_YET")
        source.publish(id, mapOf("app.yaml" to "a: 1\n"))
        mvc.get("${page(id)}/files/missing.yaml") { with(signedIn(alice)) }.andExpectProblemPage(404, "ITEM_NOT_FOUND")
        mvc.get("${page(id)}/files?revision=a-b") {
            with(signedIn(alice))
        }.andExpectProblemPage(400, "INVALID_REVISION")
        mvc.get("${page(id)}/files/a/") { with(signedIn(alice)) }.andExpectProblemPage(400, "INVALID_ITEM_PATH")
    }

    @Test
    fun `a download that fails gives the error page without the headers of the file`() {
        val id = catalog.readableConfigSet("user:alice")
        val size = 4L * 1024 * 1024
        val revision = source.publishItems(id, mapOf("large.bin" to TestItem.failing(size)))

        val response =
            mvc
                .get("${page(id)}/download/large.bin?revision=$revision") { with(signedIn(alice)) }
                .andExpectProblemPage(502, "UNREACHABLE")
                .andExpect {
                    // The error page must not keep the attachment disposition of the download. Spring sets its own
                    // inline disposition on an error for a URL with a file extension.
                    header { string(HttpHeaders.CONTENT_DISPOSITION, not(containsString("attachment"))) }
                    header { string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8") }
                }.andReturn()
                .response
        assertNotEquals(size.toString(), response.getHeader(HttpHeaders.CONTENT_LENGTH))
    }

    @Test
    fun `the pages escape HTML in item paths`() {
        val id = catalog.readableConfigSet("user:alice")
        val path = "\"><script>alert(1)</script>.yaml"
        val revision = source.publish(id, mapOf(path to "a: 1\n"))
        val encoded = path.split('/').joinToString("/") { UriUtils.encodePathSegment(it, StandardCharsets.UTF_8) }

        listOf("${page(id)}/files?revision=$revision", "${page(id)}/files/$encoded?revision=$revision").forEach { url ->
            mvc.get(URI.create(url)) { with(signedIn(alice)) }.andExpect {
                status { isOk() }
                content { string(containsString("&lt;script&gt;alert(1)")) }
                content { string(not(containsString("<script>alert"))) }
            }
        }
    }

    private fun page(id: ConfigSetId) = "/configsets/${id.toApiId()}"

    private companion object {
        /** The pages of the `content` module, relative to the page of the ConfigSet. */
        val ROUTES = listOf("/revisions", "/files", "/files/app.yaml", "/download/app.yaml")
    }
}
