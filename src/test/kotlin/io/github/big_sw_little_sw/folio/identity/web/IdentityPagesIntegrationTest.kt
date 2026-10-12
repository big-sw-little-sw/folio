package io.github.big_sw_little_sw.folio.identity.web

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.session.SessionRepository
import org.springframework.test.web.servlet.*
import java.time.Duration
import kotlin.test.*

@FolioIntegrationTest
class IdentityPagesIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val sessions: SessionRepository<*>,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-b", "team-a"), "cli")

    @Test
    fun `an anonymous caller sees a sign-in link and gets no session`() {
        mvc.get("/").andExpect {
            status { isOk() }
            content { string(containsString("""<a href="/sign-in">Sign in</a>""")) }
            content { string(not(containsString("csrf-token"))) }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }
    }

    @Test
    fun `a signed-in caller sees the caller, a sign-out form and the CSRF token for htmx`() {
        val page = mvc.page("/", alice)

        assertContains(page, "Signed in as <strong>alice</strong>")
        assertContains(page, "<code>team-a</code>")
        assertContains(page, "<code>cli</code>")
        assertContains(page, """<form method="post" action="/sign-out">""")
        assertContains(page, """<input type="hidden" name="_csrf" value=""")
        assertContains(page, """<meta name="csrf-header" content="X-CSRF-TOKEN">""")
        assertContains(page, """<meta name="csrf-token" content=""")
    }

    @Test
    fun `UI responses have a strict content security policy`() {
        mvc.get("/").andExpect {
            header { string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'") }
            header { string("X-Content-Type-Options", "nosniff") }
        }
    }

    @Test
    fun `sign-in uses the authorization code flow with PKCE`() {
        mvc.get("/sign-in").andExpect { redirectedUrl("/oauth2/authorization/folio") }
        mvc.get("/oauth2/authorization/folio").andExpect {
            status { is3xxRedirection() }
            header {
                string(
                    HttpHeaders.LOCATION,
                    allOf(
                        startsWith("${TestIssuer.uri}/authorize?"),
                        containsString("response_type=code"),
                        containsString("code_challenge_method=S256"),
                        containsString("redirect_uri=http://localhost/login/oauth2/code/folio"),
                    ),
                )
            }
        }
    }

    @Test
    fun `a failed sign-in shows a page instead of a new redirect`() {
        mvc.get("/sign-in?error").andExpect {
            status { isOk() }
            content { string(containsString("Sign-in failed")) }
        }
    }

    @Test
    fun `sign-out needs the CSRF token`() {
        mvc.post("/sign-out") { with(signedIn(alice)) }.andExpect {
            status { isForbidden() }
            content { string(containsString("<code>CSRF_TOKEN_INVALID</code>")) }
            content { string(containsString("Signed in as <strong>alice</strong>")) }
        }
        mvc
            .post("/sign-out") {
                with(signedIn(alice))
                with(csrf())
            }.andExpect {
                redirectedUrl("/")
            }
    }

    @Test
    fun `htmx requests send the CSRF token of the page in a header`() {
        val page = mvc.get("/") { with(signedIn(alice)) }.andReturn().response
        val session = requireNotNull(page.getCookie("SESSION"))
        val token = requireNotNull(CSRF_TOKEN.find(page.contentAsString)).groupValues[1]

        mvc
            .post("/sign-out") {
                with(signedIn(alice))
                cookie(session)
                header("X-CSRF-TOKEN", token)
            }.andExpect { redirectedUrl("/") }
    }

    @Test
    fun `an htmx request with a wrong CSRF header is rejected`() {
        val session =
            requireNotNull(
                mvc
                    .get("/") { with(signedIn(alice)) }
                    .andReturn()
                    .response
                    .getCookie("SESSION"),
            )

        mvc
            .post("/sign-out") {
                with(signedIn(alice))
                cookie(session)
                header("X-CSRF-TOKEN", "wrong")
            }.andExpect {
                status { isForbidden() }
                content { string(containsString("<code>CSRF_TOKEN_INVALID</code>")) }
            }
    }

    @Test
    fun `sessions end after one hour without use by default`() {
        assertEquals(Duration.ofHours(1), sessions.createSession().maxInactiveInterval)
    }

    private companion object {
        val CSRF_TOKEN = Regex("<meta name=\"csrf-token\" content=\"([^\"]+)\">")
    }
}
