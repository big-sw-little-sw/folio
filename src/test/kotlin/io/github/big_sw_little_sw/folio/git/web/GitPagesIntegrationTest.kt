package io.github.big_sw_little_sw.folio.git.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.git.internal.Synchronizer
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.*
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.*
import kotlin.test.*

@GitIntegrationTest
class GitPagesIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val git: TestGit,
    @Autowired private val audit: TestAudit,
    @Autowired private val synchronizer: Synchronizer,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `the Namespace page links to the connect form for a caller with MANAGE`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.READ, "user:alice")
        val page = "/namespaces/${namespace.toApiId()}"
        val link = "/git/configsets/new?namespaceId=${namespace.toApiId()}"

        assertFalse(link in mvc.page(page, alice))
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        assertContains(mvc.page(page, alice), """<a href="$link">Connect a Git repository</a>""")
    }

    @Test
    fun `the connect form needs MANAGE on the Namespace`() {
        val namespace = catalog.namespace()
        val page = "/git/configsets/new?namespaceId=${namespace.toApiId()}"

        mvc.get(page) { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val html = mvc.page(page, alice)
        assertContains(html, catalog.pathOf(namespace))
        assertContains(html, """action="/git/configsets"""")
    }

    @Test
    fun `the connect form creates the ConfigSet and goes to its Git source page`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val credential = credentials.credential(namespace)

        mvc
            .postForm(
                "/git/configsets",
                alice,
                "namespaceId" to namespace.toApiId(),
                "slug" to "service-a",
                "description" to "",
                "credentialId" to credential.id.toApiId(),
                "repositoryPath" to "org/repo.git",
                "branch" to "main",
                "rootPath" to "config",
            ).andExpect {
                status { is3xxRedirection() }
                redirectedUrlPattern("/configsets/cfg_*/git")
                flash { attribute("notice", startsWith("ConfigSet ${catalog.pathOf(namespace)}/service-a created.")) }
            }
    }

    @Test
    fun `the Git source page shows the Source definition and the check form`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val credential = credentials.credential(namespace)
        val page = "/configsets/${git.configSet(credential.id, namespace = namespace).toApiId()}/git"

        val html = mvc.page(page, alice)

        assertContains(html, "<code>repos/configs.git</code>")
        assertContains(html, """href="/credentials/${credential.id.toApiId()}"""")
        assertContains(html, "Not published yet")
        assertContains(html, """action="$page/check"""")
    }

    @Test
    fun `a failed connect shows the form again with the values and the error`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val credential = credentials.credential(namespace)

        mvc
            .postForm(
                "/git/configsets",
                alice,
                "namespaceId" to namespace.toApiId(),
                "slug" to "service-a",
                "credentialId" to credential.id.toApiId(),
                "repositoryPath" to "org/repo.git",
                "branch" to "a..b",
            ).andExpectProblemPage(400, "INVALID_BRANCH")
            .andExpect { content { string(containsString("""value="org/repo.git"""")) } }
    }

    @Test
    @FreshGitRepository
    fun `the ConfigSet page links to the Git source for a caller with MANAGE, and the check gives its result`(
        commits: Commits,
    ) {
        val namespace = catalog.namespace()
        val configSet = git.connected(namespace)
        catalog.rule(namespace, Action.READ, "user:alice")
        val page = "/configsets/${configSet.toApiId()}/git"

        assertFalse("""href="$page"""" in mvc.page("/configsets/${configSet.toApiId()}", alice))
        mvc.get(page) { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        assertContains(mvc.page("/configsets/${configSet.toApiId()}", alice), """<a href="$page">Git source</a>""")

        mvc.postForm("$page/check", alice).andExpect {
            status { is3xxRedirection() }
            flash { attribute("notice", "The check passed. The tip of the branch is ${commits.tip}.") }
        }
        SshGitServer.revokeAll()
        mvc.postForm("$page/check", alice).andExpect {
            flash { attribute("notice", startsWith("The check failed with AUTH_FAILED.")) }
        }
        mvc
            .postForm("$page/check", alice, "keyId" to "key_${"0".repeat(32)}")
            .andExpectProblemPage(409, "KEY_NOT_PENDING")
    }

    @Test
    @FreshGitRepository
    fun `the Git source page shows the last published revision`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val configSet = git.connected(namespace)

        val tip = git.publishTip(configSet)

        val html = mvc.page("/configsets/${configSet.toApiId()}/git", alice)
        assertContains(html, "<code>$tip</code>")
        assertFalse("Not published yet" in html)
    }

    @Test
    fun `the Git source page shows the sync state, and the sync form makes the ConfigSet due`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val credential = credentials.credential(namespace, gitInstance = "nowhere").id
        val configSet = git.configSet(credential, namespace = namespace)
        val page = "/configsets/${configSet.toApiId()}/git"

        val html = mvc.page(page, alice)
        assertContains(html, "Not synced yet")
        assertContains(html, """action="$page/sync"""")
        // A new ConfigSet is due at creation. After parkAll, only the form can make the ConfigSet due.
        git.parkAll()
        mvc.postForm("$page/sync", alice).andExpect {
            status { is3xxRedirection() }
            redirectedUrl(page)
            flash { attribute("notice", "The ConfigSet is due now. The next poll of an instance syncs it.") }
        }
        syncDue(configSet)
        val requested = audit.records(configSet.value).single { it["operation"] == "SYNC_REQUESTED" }
        assertEquals("alice", requested["actor_subject"])
        val synced = mvc.page(page, alice)
        assertContains(synced, "<code>GIT_INSTANCE_NOT_CONFIGURED</code>")
        assertContains(synced, "Consecutive failures: 1.")
    }

    @Test
    @FreshGitRepository
    fun `the Git source page shows a running sync, and a successful sync`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val configSet = git.connected(namespace)
        val page = "/configsets/${configSet.toApiId()}/git"

        val lease = git.claim(configSet)
        assertContains(mvc.page(page, alice), "Syncing now")
        synchronizer.sync(lease)

        val html = mvc.page(page, alice)
        assertContains(html, "Synced")
        assertFalse("Syncing now" in html)
    }

    @Test
    fun `the sync form needs MANAGE on the ConfigSet`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.READ, "user:alice")
        val configSet = git.configSet(credentials.credential(namespace).id, namespace = namespace)

        mvc.postForm(
            "/configsets/${configSet.toApiId()}/git/sync",
            alice,
        ).andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
    }

    @Test
    fun `the forms need a CSRF token`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val configSet = git.configSet(credentials.credential(namespace).id, namespace = namespace)

        mvc.post("/configsets/${configSet.toApiId()}/git/check") { with(signedIn(alice)) }.andExpect {
            status { isForbidden() }
        }
        mvc.post("/configsets/${configSet.toApiId()}/git/sync") { with(signedIn(alice)) }.andExpect {
            status { isForbidden() }
        }
    }

    /** Claims the due ConfigSets, requires that [configSet] is the only one, and syncs it. */
    private fun syncDue(configSet: ConfigSetId) {
        val lease = synchronizer.claim(CLAIM_LIMIT).single()
        assertEquals(configSet, lease.configSet)
        synchronizer.sync(lease)
    }

    private companion object {
        const val CLAIM_LIMIT = 10
    }
}
