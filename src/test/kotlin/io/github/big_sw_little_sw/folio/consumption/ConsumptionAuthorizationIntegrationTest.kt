package io.github.big_sw_little_sw.folio.consumption

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.source.SshGitServer
import io.github.big_sw_little_sw.folio.sync.SyncFixture
import io.github.big_sw_little_sw.folio.sync.internal.Synchronizer
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
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
import kotlin.test.assertEquals

/**
 * Every consumption route needs its action on the ConfigSet, anonymous callers included, so that `public` rules apply
 * (ADR 0035). Responses that everyone may read may be stored in shared caches.
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsumptionAuthorizationIntegrationTest(
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
        fixture.sync()
    }

    @Test
    fun `anonymous callers without a public rule get 401 with a bearer challenge on every route, never cached`() {
        ROUTES.forEach { (route, action) ->
            fixture.get(fixture.url + route, token = null).andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")) }
                header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
                jsonPath("$.detail") { value("Authentication required for $action") }
            }
        }
    }

    @Test
    fun `a public rule lets anonymous callers read, and shared caches may store the responses`() {
        ROUTES.values.toSet().forEach { fixture.grant(it, "public") }

        ROUTES.keys.forEach { route ->
            fixture.get(fixture.url + route, token = null).andExpect {
                status { isOk() }
                header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=30") }
            }
        }
        fixture.get("${fixture.url}/files/app.yaml?revision=${fixture.commits.last()}", token = null).andExpect {
            status { isOk() }
            header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, s-maxage=30, immutable") }
        }
    }

    @Test
    fun `a public rule inherited from the namespace makes responses public too`() {
        fixture.grantOnNamespace("CONFIG_ITEM_READ", "public")

        fixture.get("${fixture.url}/files", token = null).andExpect {
            status { isOk() }
            header { string(HttpHeaders.CACHE_CONTROL, "public, max-age=30") }
        }
    }

    @Test
    fun `a public view rule alone does not let anonymous callers read files`() {
        fixture.grant("CONFIG_SET_VIEW", "public")

        fixture.get(fixture.url, token = null).andExpect { status { isOk() } }
        fixture.get("${fixture.url}/files", token = null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Authentication required for CONFIG_ITEM_READ") }
        }
    }

    @Test
    fun `a matching If-None-Match does not get past authorization`() {
        val etags =
            ROUTES.keys.associateWith {
                fixture
                    .get(fixture.url + it)
                    .andReturn()
                    .response
                    .getHeader("ETag")
            }

        etags.forEach { (route, etag) ->
            fixture.get(fixture.url + route, token = null, ifNoneMatch = etag).andExpect { status { isUnauthorized() } }
            fixture.get(fixture.url + route, ConsumptionFixture.ALICE, etag).andExpect { status { isForbidden() } }
        }
    }

    @Test
    fun `authenticated callers get 403 without the route's action and private caching with it`() {
        ROUTES.forEach { (route, action) ->
            fixture.get(fixture.url + route, ConsumptionFixture.ALICE).andExpect {
                status { isForbidden() }
                header { string(HttpHeaders.CACHE_CONTROL, containsString("no-store")) }
                jsonPath("$.detail") { value("Permission $action denied") }
            }
        }
        ROUTES.values.toSet().forEach { fixture.grant(it, "user:alice") }
        ROUTES.keys.forEach { route ->
            fixture.get(fixture.url + route, ConsumptionFixture.ALICE).andExpect {
                status { isOk() }
                header { string(HttpHeaders.CACHE_CONTROL, "private, max-age=30") }
            }
        }
    }

    @Test
    fun `a missing ConfigSet gives 404 to anonymous callers too`() {
        ROUTES.keys.forEach { route ->
            fixture.get("/api/v1/configsets/cfg_${"0".repeat(32)}$route", token = null).andExpect {
                status { isNotFound() }
            }
        }
    }

    @Test
    fun `anonymous resolve answers a missing and a hidden path with the same 401, a public one with the ConfigSet`() {
        val path = "production/${fixture.configSet.slug.value}"
        val hidden = fixture.get("$RESOLVE?path=$path", token = null).andReturn().response

        assertEquals(401, hidden.status)
        // A missing ConfigSet and a missing namespace above it answer alike. Problem details name the request path in
        // `instance`, which differs by the query only.
        listOf("production/missing", "missing/${fixture.configSet.slug.value}").forEach {
            val missing = fixture.get("$RESOLVE?path=$it", token = null).andReturn().response
            assertEquals(hidden.status, missing.status)
            assertEquals(
                hidden.getHeader(HttpHeaders.WWW_AUTHENTICATE),
                missing.getHeader(HttpHeaders.WWW_AUTHENTICATE),
            )
            assertEquals(hidden.contentAsString, missing.contentAsString)
        }

        fixture.grant("CONFIG_SET_VIEW", "public")
        fixture.get("$RESOLVE?path=$path", token = null).andExpect {
            status { isOk() }
            jsonPath("$.path") { value(path) }
        }
    }

    companion object {
        private const val REPOSITORY = "consumption-authorization"
        private const val RESOLVE = "/api/v1/configsets:resolve"

        /** Each route beneath the ConfigSet's URL and the action it needs. */
        private val ROUTES =
            mapOf(
                "" to "CONFIG_SET_VIEW",
                "/files" to "CONFIG_ITEM_READ",
                "/files/app.yaml" to "CONFIG_ITEM_READ",
                "/revisions" to "CONFIG_VERSION_LIST",
            )

        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
