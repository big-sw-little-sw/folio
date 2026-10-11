package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.signedIn
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test

/** The rules page with its explain form (design 12.3). It needs `GRANT` only. */
@FolioIntegrationTest
class RulePagesIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `the rules page puts and deletes rules`() {
        val top = catalog.namespace()
        val rules = "/namespaces/${top.toApiId()}/rules"

        post("$rules/READ", "subjects" to "group:team-a\n\n  user:bob  ").andExpect { redirectedUrl(rules) }
        mvc.get(rules) { with(signedIn(SUPER_ADMIN)) }.andExpect {
            status { isOk() }
            content { string(containsString("group:team-a\nuser:bob</textarea>")) }
        }
        post("$rules/READ", "subjects" to "nobody").andExpect {
            status { isBadRequest() }
            content {
                string(
                    allOf(containsString("<code>INVALID_SUBJECT</code>"), containsString("nobody</textarea>")),
                )
            }
        }
        post("$rules/READ/delete").andExpect { redirectedUrl(rules) }
        mvc.get(rules) { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(containsString("No rule. READ inherits.")) }
        }
    }

    @Test
    fun `a caller with only GRANT opens the rules page and puts a rule`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.GRANT, "user:alice")
        val rules = "/namespaces/${top.toApiId()}/rules"

        mvc.get(rules) { with(signedIn(alice)) }.andExpect { status { isOk() } }
        mvc.get("$rules?action=READ&subject=bob") { with(signedIn(alice)) }.andExpect {
            content { string(containsString("No rule for READ exists on this resource or above it.")) }
        }
        mvc
            .post("$rules/READ") {
                with(signedIn(alice))
                with(csrf())
                param("subjects", "user:bob")
            }.andExpect { redirectedUrl(rules) }
    }

    @Test
    fun `the rules page explains a decision`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.READ, "group:team-a")
        val rules = "/namespaces/${top.toApiId()}/rules"

        mvc
            .get("$rules?action=READ&subject=alice&groups=team-a#explain") { with(signedIn(SUPER_ADMIN)) }
            .andExpect {
                content {
                    string(
                        allOf(
                            containsString("<strong>Permitted</strong>"),
                            containsString("Its subject <code>group:team-a</code> matched."),
                        ),
                    )
                }
            }
        mvc.get("$rules?action=READ") { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(containsString("None of its subjects matched.")) }
        }
        mvc.get("$rules?action=MANAGE&subject=alice") { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(containsString("No rule for MANAGE exists on this resource or above it.")) }
        }
        mvc.get("$rules?action=GRANT&subject=x&groups=folio-admins") { with(signedIn(SUPER_ADMIN)) }.andExpect {
            content { string(containsString("The super-admin entry <code>group:folio-admins</code> matched.")) }
        }
    }

    private fun post(url: String, vararg params: Pair<String, String>) = mvc.post(url) {
        with(signedIn(SUPER_ADMIN))
        with(csrf())
        params.forEach { (name, value) -> param(name, value) }
    }
}
