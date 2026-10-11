package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.git.internal.GitProperties
import io.github.big_sw_little_sw.folio.git.internal.RepositoryCache
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.testing.GitIntegrationTest
import io.github.big_sw_little_sw.testing.SshGitServer
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestCredentials
import io.github.big_sw_little_sw.testing.TestGit
import io.github.big_sw_little_sw.testing.activeKey
import io.github.big_sw_little_sw.testing.andExpectForbidden
import io.github.big_sw_little_sw.testing.andExpectProblem
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.send
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * These tests check the Git check (design 10.3) against the SSH Git server, through HTTP. A failed check is a result
 * with status 200. The tests share the server, so each test sets the authorized keys that it needs.
 */
@GitIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class GitCheckIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val git: TestGit,
    @Autowired private val properties: GitProperties,
    @Autowired private val cache: RepositoryCache,
) {
    private lateinit var commits: List<String>
    private lateinit var credential: Credential

    @BeforeEach
    fun setUp() {
        SshGitServer.revokeAll()
        commits = SshGitServer.createRepository("configs")
        credential = git.authorizedCredential()
    }

    @Test
    fun `the check passes when the key reaches the repository, the branch exists and the root path is a directory`() {
        listOf("config", "", "config/sub").forEach { root ->
            check(git.configSet(credential.id, rootPath = root)).andExpectPassed(commits.last())
        }
    }

    @Test
    fun `the check does not publish a revision`() {
        val configSet = git.configSet(credential.id)

        check(configSet).andExpectPassed(commits.last())

        mvc.get("/api/v1/configsets/${configSet.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            status { isOk() }
            jsonPath("$.latestRevision") { value(null) }
        }
    }

    @Test
    fun `each trusted host key of an instance is accepted, not only the first`() {
        val multiKey = credentials.credential(gitInstance = "multi-key")
        SshGitServer.authorize(multiKey.activeKey.publicKeyLine)

        check(git.configSet(multiKey.id)).andExpectPassed(commits.last())
    }

    @Test
    fun `a host key that the instance does not list fails the check, and Folio fetches nothing`() {
        val wrongHostKey = credentials.credential(gitInstance = "wrong-host-key")
        SshGitServer.authorize(wrongHostKey.activeKey.publicKeyLine)
        val configSet = git.configSet(wrongHostKey.id)

        check(configSet).andExpectFailed("HOST_KEY_REJECTED")
        assertFalse(properties.cacheDirectory.resolve(configSet.value.toString()).exists())
    }

    @Test
    fun `Folio ignores known hosts, keys and config in the SSH home directory`() {
        val ssh = properties.cacheDirectory.resolve("ssh-home/.ssh").createDirectories()
        val identity = SshGitServer.newIdentity()
        try {
            ssh.resolve("known_hosts").writeText("${SshGitServer.knownHostsName} ${SshGitServer.hostKey}\n")
            ssh.resolve("id_ed25519").writeText(identity.privateKey)
            Files.setPosixFilePermissions(ssh.resolve("id_ed25519"), PosixFilePermissions.fromString("rw-------"))
            ssh.resolve("config").writeText("Host *\n  StrictHostKeyChecking no\n  IdentityFile ~/.ssh/id_ed25519\n")
            SshGitServer.revokeAll()
            SshGitServer.authorize(identity.publicKeyLine)
            val wrongHostKey = credentials.credential(gitInstance = "wrong-host-key")

            // Only known_hosts has the real host key, and only the SSH directory has the authorized key. Folio uses
            // neither file, so both checks fail.
            check(git.configSet(wrongHostKey.id)).andExpectFailed("HOST_KEY_REJECTED")
            check(git.configSet(credential.id)).andExpectFailed("AUTH_FAILED")
        } finally {
            listOf("known_hosts", "id_ed25519", "config").forEach { ssh.resolve(it).deleteIfExists() }
        }
    }

    @Test
    fun `a key that the server does not accept fails with AUTH_FAILED`() {
        SshGitServer.revokeAll()

        check(git.configSet(credential.id)).andExpectFailed("AUTH_FAILED")
    }

    @Test
    fun `a missing repository, branch or root path fails with its own code`() {
        check(git.configSet(credential.id, repository = "missing")).andExpectFailed("REPOSITORY_NOT_FOUND")
        check(git.configSet(credential.id, branch = "missing")).andExpectFailed("BRANCH_NOT_FOUND")
        check(git.configSet(credential.id, rootPath = "missing")).andExpectFailed("ROOT_PATH_NOT_FOUND")
        // A root path must be a directory, so a file fails too.
        check(git.configSet(credential.id, rootPath = "config/app.yaml")).andExpectFailed("ROOT_PATH_NOT_FOUND")
    }

    @Test
    fun `a server that Folio cannot connect to fails with UNREACHABLE`() {
        val unreachable = credentials.credential(gitInstance = "unreachable")

        check(git.configSet(unreachable.id)).andExpectFailed("UNREACHABLE")
    }

    @Test
    fun `a check with a pending key uses that key, so the key can be tested before its activation`() {
        val pending = credentials.pendingKey(credential.id)
        SshGitServer.revokeAll()
        SshGitServer.authorize(pending.publicKeyLine)
        val configSet = git.configSet(credential.id)

        check(configSet, pending.id).andExpectPassed(commits.last())
        check(configSet).andExpectFailed("AUTH_FAILED")

        credentials.activate(credential.id, pending.id)
        check(configSet).andExpectPassed(commits.last())
    }

    /**
     * The fetch fetches only the configured branch, without tags (design 10.5). The repository has the branch `other`
     * and the annotated tag `v1` on a commit of `main`.
     */
    @Test
    fun `the cache gets only the configured branch and no tags`() {
        val configSet = git.configSet(credential.id)

        check(configSet).andExpectPassed(commits.last())

        val (refs, otherBranch, tag) =
            cache.reading(configSet, { fail("The check made no repository.") }) { repository ->
                val objects = repository.objectDatabase
                Triple(
                    repository.refDatabase.getRefsByPrefix("refs/").map { it.name },
                    objects.has(ObjectId.fromString(SshGitServer.objectId("configs", "refs/heads/other"))),
                    objects.has(ObjectId.fromString(SshGitServer.objectId("configs", "refs/tags/v1"))),
                )
            }
        assertEquals(listOf("refs/heads/main"), refs)
        assertFalse(otherBranch)
        assertFalse(tag)
    }

    @Test
    fun `a check with a key that is not the pending key is a conflict`() {
        val configSet = git.configSet(credential.id)
        val active = credential.activeKey.id

        check(configSet, active).andExpectProblem(409, "KEY_NOT_PENDING")
        mvc
            .send(
                HttpMethod.POST,
                "$CONFIGSETS/${configSet.toApiId()}/git:check",
                SUPER_ADMIN,
                """{"keyId": "key_1"}""",
            )
            .andExpectProblem(400, "INVALID_ID")
    }

    @Test
    fun `a disabled credential is a result, after the authorization`() {
        val namespace = catalog.namespace()
        val configSet = git.configSet(credential.id, namespace = namespace)
        credentials.disable(credential.id)
        val alice = Caller.Authenticated("alice", emptySet(), null)
        catalog.rule(namespace, Action.MANAGE, "user:alice")

        check(configSet).andExpectFailed("CREDENTIAL_DISABLED")
        check(configSet, caller = alice).andExpectForbidden(Action.MANAGE, credential.namespaceId.toApiId())
    }

    /**
     * A wrong key ID is a wrong request, so `KEY_NOT_PENDING` comes before each failure of the source, also before
     * `GIT_INSTANCE_NOT_CONFIGURED` (design 10.3).
     */
    @Test
    fun `a credential whose Git instance the configuration lacks fails with GIT_INSTANCE_NOT_CONFIGURED`() {
        val removed = credentials.credential(gitInstance = "removed")
        val configSet = git.configSet(removed.id)

        check(configSet).andExpectFailed("GIT_INSTANCE_NOT_CONFIGURED")
        check(configSet, removed.activeKey.id).andExpectProblem(409, "KEY_NOT_PENDING")
    }

    /** A caller without MANAGE on the Namespace of the credential never learns the state of its keys (design 11.1). */
    @Test
    fun `a wrong key ID gives 403, not KEY_NOT_PENDING, to a caller without MANAGE on the credential`() {
        val namespace = catalog.namespace()
        val configSet = git.configSet(credential.id, namespace = namespace)
        val alice = Caller.Authenticated("alice", emptySet(), null)
        catalog.rule(namespace, Action.MANAGE, "user:alice")

        check(configSet, credential.activeKey.id, alice).andExpectForbidden(
            Action.MANAGE,
            credential.namespaceId.toApiId(),
        )
    }

    @Test
    fun `the check needs MANAGE on the ConfigSet and on the Namespace of the credential`() {
        val namespace = catalog.namespace()
        val configSet = git.configSet(credential.id, namespace = namespace)
        val alice = Caller.Authenticated("alice", emptySet(), null)

        check(configSet, caller = alice).andExpectForbidden(Action.MANAGE, configSet.toApiId())
        catalog.rule(namespace, Action.MANAGE, "user:alice")
        check(configSet, caller = alice).andExpectForbidden(Action.MANAGE, credential.namespaceId.toApiId())
        catalog.rule(credential.namespaceId, Action.MANAGE, "user:alice")
        check(configSet, caller = alice).andExpectPassed(commits.last())
        mvc.post("$CONFIGSETS/${configSet.toApiId()}/git:check").andExpectProblem(401, "AUTHENTICATION_REQUIRED")
        mvc
            .post("$CONFIGSETS/cfg_${"0".repeat(32)}/git:check") { bearer(SUPER_ADMIN) }
            .andExpectProblem(404, "CONFIG_SET_NOT_FOUND")
    }

    @Test
    fun `the log of a failed check has the code and the exception types, and no server output`(output: CapturedOutput) {
        val configSet = git.configSet(credential.id, repository = "missing")

        check(configSet).andExpectFailed("REPOSITORY_NOT_FOUND")

        val line = "ConfigSet ${configSet.toApiId()} failed with REPOSITORY_NOT_FOUND"
        // One failure gives exactly one log line, so the split gives two parts.
        assertEquals(2, output.all.split(line).size, output.all)
        assertTrue("NoRemoteRepositoryException" in output.all, output.all)
        // The error message of the server names the repository, so the log must not contain that message.
        assertFalse("missing.git" in output.all, output.all)
        assertFalse("does not appear to be a git repository" in output.all, output.all)
    }

    /** The messages of these loggers can contain server output, so the configuration sets them to OFF (design 10.4). */
    @Test
    fun `the JGit and Apache sshd loggers are off`() {
        listOf("org.apache.sshd", "org.eclipse.jgit").forEach {
            assertFalse(LoggerFactory.getLogger(it).isErrorEnabled, it)
        }
    }

    private fun check(
        configSet: ConfigSetId,
        key: KeyId? = null,
        caller: Caller.Authenticated = SUPER_ADMIN,
    ): ResultActionsDsl = mvc.send(
        HttpMethod.POST,
        "$CONFIGSETS/${configSet.toApiId()}/git:check",
        caller,
        key?.let { """{"keyId": "${it.toApiId()}"}""" },
    )

    private fun ResultActionsDsl.andExpectPassed(commitId: String) = andExpect {
        status { isOk() }
        jsonPath("$.ok") { value(true) }
        jsonPath("$.commitId") { value(commitId) }
        jsonPath("$.code") { doesNotExist() }
    }

    private fun ResultActionsDsl.andExpectFailed(code: String) = andExpect {
        status { isOk() }
        jsonPath("$.ok") { value(false) }
        jsonPath("$.code") { value(code) }
        jsonPath("$.summary") { exists() }
        jsonPath("$.commitId") { doesNotExist() }
    }

    private companion object {
        const val CONFIGSETS = "/api/v1/admin/configsets"
    }
}
