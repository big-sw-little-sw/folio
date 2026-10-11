package io.github.big_sw_little_sw.folio.git.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.GitIntegrationTest
import io.github.big_sw_little_sw.testing.SshGitServer
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCredentials
import io.github.big_sw_little_sw.testing.TestGit
import io.github.big_sw_little_sw.testing.andExpectProblemPage
import io.github.big_sw_little_sw.testing.postForm
import io.github.big_sw_little_sw.testing.signedIn
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test

/** These tests check the Git pages (design 12.3) through MockMvc with signed-in test sessions. */
@GitIntegrationTest
class GitPagesIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val git: TestGit,
) {
    private val alice = Caller.Authenticated("alice", setOf("team-a"), null)

    @Test
    fun `the Namespace page links to the connect form for a caller with MANAGE`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.READ, "user:alice")
        val link = "/git/configsets/new?namespaceId=${namespace.toApiId()}"

        mvc.get("/namespaces/${namespace.toApiId()}") { with(signedIn(alice)) }.andExpect {
            content { string(not(containsString(link))) }
        }
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        mvc.get("/namespaces/${namespace.toApiId()}") { with(signedIn(alice)) }.andExpect {
            content { string(containsString("""<a href="$link">Connect a Git repository</a>""")) }
        }
    }

    @Test
    fun `the connect form needs MANAGE on the Namespace`() {
        val namespace = catalog.namespace()
        val page = "/git/configsets/new?namespaceId=${namespace.toApiId()}"

        mvc.get(page) { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        mvc
            .get(page) { with(signedIn(alice)) }
            .andExpect {
                status { isOk() }
                content {
                    string(
                        allOf(
                            containsString(catalog.pathOf(namespace)),
                            containsString("""action="/git/configsets""""),
                        ),
                    )
                }
            }
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

        mvc
            .get(page) { with(signedIn(alice)) }
            .andExpect {
                status { isOk() }
                content {
                    string(
                        allOf(
                            containsString("<code>repos/configs.git</code>"),
                            containsString("""href="/credentials/${credential.id.toApiId()}""""),
                            containsString("Not published yet"),
                            containsString("""action="$page/check""""),
                        ),
                    )
                }
            }
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
    fun `the ConfigSet page links to the Git source for a caller with MANAGE, and the check gives its result`() {
        SshGitServer.revokeAll()
        val commits = SshGitServer.createRepository("configs")
        val namespace = catalog.namespace()
        val credential = git.authorizedCredential(namespace)
        val configSet = git.configSet(credential.id, namespace = namespace)
        catalog.rule(namespace, Action.READ, "user:alice")
        val page = "/configsets/${configSet.toApiId()}/git"

        mvc.get("/configsets/${configSet.toApiId()}") { with(signedIn(alice)) }.andExpect {
            content { string(not(containsString("""href="$page""""))) }
        }
        mvc.get(page) { with(signedIn(alice)) }.andExpectProblemPage(403, "ACTION_NOT_ALLOWED")
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        mvc.get("/configsets/${configSet.toApiId()}") { with(signedIn(alice)) }.andExpect {
            content { string(containsString("""<a href="$page">Git source</a>""")) }
        }

        mvc.postForm("$page/check", alice).andExpect {
            status { is3xxRedirection() }
            flash { attribute("notice", "The check passed. The tip of the branch is ${commits.last()}.") }
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
    fun `the Git source page shows the last published revision`() {
        SshGitServer.revokeAll()
        SshGitServer.createRepository("configs")
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val configSet = git.configSet(git.authorizedCredential(namespace).id, namespace = namespace)

        val tip = git.publishTip(configSet)

        mvc.get("/configsets/${configSet.toApiId()}/git") { with(signedIn(alice)) }.andExpect {
            content { string(allOf(containsString("<code>$tip</code>"), not(containsString("Not published yet")))) }
        }
    }

    @Test
    fun `the forms need a CSRF token`() {
        val namespace = catalog.namespace()
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        val configSet = git.configSet(credentials.credential(namespace).id, namespace = namespace)

        mvc.post("/configsets/${configSet.toApiId()}/git/check") { with(signedIn(alice)) }.andExpect {
            status { isForbidden() }
        }
    }
}
