package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.*
import kotlin.test.*

/** The rules page needs `GRANT` and no other action. */
@FolioIntegrationTest
class RulePagesIntegrationTest(@Autowired private val mvc: MockMvc, @Autowired private val catalog: TestCatalog) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `the rules page puts and deletes rules`() {
        val top = catalog.namespace()
        val rules = "/namespaces/${top.toApiId()}/rules"

        post("$rules/READ", "subjects" to "group:team-a\n\n  user:bob  ").andExpect { redirectedUrl(rules) }
        assertContains(mvc.page(rules, SUPER_ADMIN), "group:team-a\nuser:bob</textarea>")
        val failed = post("$rules/READ", "subjects" to "nobody").andExpectProblemPage(400, "INVALID_SUBJECT")
        assertContains(failed.andReturn().response.contentAsString, "nobody</textarea>")
        post("$rules/READ/delete").andExpect { redirectedUrl(rules) }
        assertContains(mvc.page(rules, SUPER_ADMIN), "No rule. READ inherits.")
    }

    @Test
    fun `a caller with only GRANT opens the rules page and puts a rule`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.GRANT, "user:alice")
        val rules = "/namespaces/${top.toApiId()}/rules"

        mvc.page(rules, alice)
        assertContains(
            mvc.page("$rules?action=READ&subject=bob", alice),
            "No rule for READ exists on this resource or above it.",
        )
        mvc.postForm("$rules/READ", alice, "subjects" to "user:bob").andExpect { redirectedUrl(rules) }
    }

    @Test
    fun `the rules page explains a decision`() {
        val top = catalog.namespace()
        catalog.rule(top, Action.READ, "group:team-a")
        val rules = "/namespaces/${top.toApiId()}/rules"

        val permitted = mvc.page("$rules?action=READ&subject=alice&groups=team-a#explain", SUPER_ADMIN)
        assertContains(permitted, "<strong>Permitted</strong>")
        assertContains(permitted, "Its subject <code>group:team-a</code> matched.")
        assertContains(mvc.page("$rules?action=READ", SUPER_ADMIN), "None of its subjects matched.")
        assertContains(
            mvc.page("$rules?action=MANAGE&subject=alice", SUPER_ADMIN),
            "No rule for MANAGE exists on this resource or above it.",
        )
        assertContains(
            mvc.page("$rules?action=GRANT&subject=x&groups=folio-admins", SUPER_ADMIN),
            "The super-admin entry <code>group:folio-admins</code> matched.",
        )
    }

    private fun post(url: String, vararg params: Pair<String, String>) = mvc.postForm(url, SUPER_ADMIN, *params)
}
