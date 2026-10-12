package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.servlet.*
import java.util.UUID
import kotlin.test.*

@FolioIntegrationTest
class ConfigSetApiIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `get shows the ConfigSet to a caller that can READ it`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search")
        val serviceA = catalog.configSet(search, "service-a", "The search service")
        catalog.rule(search, Action.READ, "user:alice")

        mvc.send(GET, url(serviceA), alice).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(serviceA.toApiId()) }
            jsonPath("$.namespaceId") { value(search.toApiId()) }
            jsonPath("$.slug") { value("service-a") }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/search/service-a") }
            jsonPath("$.description") { value("The search service") }
            jsonPath("$.sourceType") { value("GIT") }
        }
    }

    @Test
    fun `get finds the ConfigSet first, then authorizes`() {
        val serviceA = catalog.configSet(catalog.namespace())
        val missing = ConfigSetId(UUID.randomUUID())

        mvc.send(GET, url(missing), alice).andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
        mvc.send(GET, url(serviceA), alice).andExpectForbidden(Action.READ, serviceA.toApiId())
    }

    @Test
    fun `a rename keeps the ID, and the old path stops resolving immediately`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a")
        catalog.rule(top, Action.READ, "public")
        catalog.rule(top, Action.MANAGE, "user:alice")

        mvc.send(PATCH, url(serviceA), alice, """{"slug": "service-b", "description": "Renamed"}""").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(serviceA.toApiId()) }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/service-b") }
            jsonPath("$.description") { value("Renamed") }
        }
        resolve("${catalog.pathOf(top)}/service-a", alice).andExpectProblem(404, "PATH_NOT_FOUND")
        resolve("${catalog.pathOf(top)}/service-b", null).andExpect { jsonPath("$.id") { value(serviceA.toApiId()) } }
        catalog.configSet(top, "service-a")
    }

    @Test
    fun `slugs are unique in a Namespace, and a Namespace and a ConfigSet can share a slug`() {
        val top = catalog.namespace()
        catalog.configSet(top, "taken")
        val other = catalog.configSet(top, "other")
        catalog.namespace(top, "other")

        mvc.send(PATCH, url(other), SUPER_ADMIN, """{"slug": "taken"}""").andExpectProblem(409, "SLUG_TAKEN")
        mvc.send(PATCH, url(other), SUPER_ADMIN, """{"slug": "Bad"}""").andExpectProblem(400, "INVALID_SLUG")
    }

    @Test
    fun `a move needs MANAGE on the ConfigSet and on the Namespace, and GRANT if the ConfigSet has rules`() {
        val top = catalog.namespace()
        val from = catalog.namespace(top, "from")
        val to = catalog.namespace(top, "to")
        val plain = catalog.configSet(from, "plain")
        val withRules = catalog.configSet(from, "with-rules")
        catalog.rule(withRules, Action.READ, "public")
        catalog.rule(from, Action.MANAGE, "user:alice")

        move(alice, plain, to).andExpectForbidden(Action.MANAGE, to.toApiId())
        catalog.rule(to, Action.MANAGE, "user:alice")
        move(alice, plain, to).andExpect {
            status { isOk() }
            jsonPath("$.namespaceId") { value(to.toApiId()) }
            jsonPath("$.path") { value("${catalog.pathOf(top)}/to/plain") }
        }
        move(alice, withRules, to).andExpectForbidden(Action.GRANT, to.toApiId())
        move(alice, withRules, from).andExpect { status { isOk() } }
        move(alice, plain, NamespaceId(UUID.randomUUID())).andExpectProblem(404, "NAMESPACE_NOT_FOUND")
        mvc.send(POST, "${url(plain)}:move", alice, "{}").andExpectProblem(400, "INVALID_REQUEST")
    }

    @Test
    fun `a delete removes the ConfigSet`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a")
        catalog.rule(serviceA, Action.READ, "public")

        mvc.send(DELETE, url(serviceA), alice).andExpectProblem(403, "ACTION_NOT_ALLOWED")
        mvc.send(DELETE, url(serviceA), SUPER_ADMIN).andExpect { status { isNoContent() } }

        mvc.send(GET, url(serviceA), SUPER_ADMIN).andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
        resolve("${catalog.pathOf(top)}/service-a", null).andExpectProblem(401, "AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `resolve gives the ID and the path`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(catalog.namespace(top, "search"), "service-a")
        catalog.rule(top, Action.READ, "group:team-a")
        val path = "${catalog.pathOf(top)}/search/service-a"

        resolve(path, alice).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(serviceA.toApiId()) }
            jsonPath("$.path") { value(path) }
        }
    }

    @Test
    fun `the last segment is always the ConfigSet`() {
        val top = catalog.namespace()
        val search = catalog.namespace(top, "search")
        catalog.namespace(search, "service-a")
        val serviceA = catalog.configSet(search, "service-a")
        catalog.rule(top, Action.READ, "public")

        resolve("${catalog.pathOf(top)}/search/service-a", null).andExpect {
            jsonPath("$.id") { value(serviceA.toApiId()) }
        }
        resolve("${catalog.pathOf(top)}/search", alice).andExpectProblem(404, "PATH_NOT_FOUND")
    }

    @Test
    fun `an authenticated caller gets the same 404 for a missing path and for a path that it cannot read`() {
        val top = catalog.namespace()
        catalog.configSet(top, "hidden")

        val missing = resolve("${catalog.pathOf(top)}/missing", alice).andExpectProblem(404, "PATH_NOT_FOUND")
        val hidden = resolve("${catalog.pathOf(top)}/hidden", alice).andExpectProblem(404, "PATH_NOT_FOUND")

        assertEquals(bodyWithoutInstance(missing), bodyWithoutInstance(hidden))
    }

    @Test
    fun `an anonymous caller gets the same 401 for a missing path and for a path that it cannot read`() {
        val top = catalog.namespace()
        catalog.configSet(top, "hidden")

        val missing = resolve("${catalog.pathOf(top)}/missing", null).andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        val hidden = resolve("${catalog.pathOf(top)}/hidden", null).andExpectProblem(401, "AUTHENTICATION_REQUIRED")

        assertEquals(bodyWithoutInstance(missing), bodyWithoutInstance(hidden))
    }

    @Test
    fun `an anonymous caller resolves a public ConfigSet`() {
        val top = catalog.namespace()
        val serviceA = catalog.configSet(top, "service-a")
        catalog.rule(serviceA, Action.READ, "public")

        resolve("${catalog.pathOf(top)}/service-a", null).andExpect { jsonPath("$.id") { value(serviceA.toApiId()) } }
    }

    @Test
    fun `resolve rejects malformed paths`() {
        listOf("", "/a/b", "a/b/", "a//b", "A/b", "only-one").forEach { path ->
            resolve(path, alice).andExpectProblem(400, "INVALID_PATH")
        }
        mvc.send(GET, RESOLVE, null).andExpectProblem(400, "INVALID_REQUEST")
    }

    private fun resolve(path: String, caller: Caller.Authenticated?) = mvc.send(GET, "$RESOLVE?path=$path", caller)

    private fun move(caller: Caller.Authenticated, id: ConfigSetId, namespace: NamespaceId) =
        mvc.send(POST, "${url(id)}:move", caller, """{"namespaceId": "${namespace.toApiId()}"}""")

    private fun bodyWithoutInstance(result: ResultActionsDsl) = result
        .andReturn()
        .response.contentAsString
        .replace(Regex("\"instance\":\"[^\"]*\""), "")

    private fun url(id: ConfigSetId) = "/api/v1/admin/configsets/${id.toApiId()}"

    private companion object {
        const val RESOLVE = "/api/v1/configsets:resolve"
    }
}
