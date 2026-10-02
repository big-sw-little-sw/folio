package io.github.big_sw_little_sw.folio.source

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialDisabledException
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.credential.GitInstanceNotConfiguredException
import io.github.big_sw_little_sw.folio.credential.KeyStatus
import io.github.big_sw_little_sw.folio.namespace.Namespace
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import io.github.big_sw_little_sw.folio.source.internal.SourceProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The onboarding check, fetches and reads against a real SSH Git server (ADR 0004). */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SourceAccessIntegrationTest(
    @Autowired private val configSets: ConfigSetService,
    @Autowired private val credentials: CredentialService,
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val sources: SourceAccess,
    @Autowired private val properties: SourceProperties,
    @Autowired private val jdbc: JdbcClient,
) {
    private lateinit var namespace: Namespace
    private lateinit var credential: Credential
    private lateinit var commits: List<String>

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
        SshGitServer.revokeAll()
        commits = SshGitServer.createRepository("configs")
        authenticateAs(SUPER_ADMIN)
        namespace = namespaces.create(null, Slug("production"))
        credential = credentials.create("test-server")
        SshGitServer.authorize(credential.key(KeyStatus.ACTIVE))
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the check passes when the key reaches the repository, the branch exists and the root path is a directory`() {
        assertEquals(SourceCheck.Passed(commits.last()), check(configSet()))
        assertEquals(SourceCheck.Passed(commits.last()), check(configSet(rootPath = "")))
        assertEquals(SourceCheck.Passed(commits.last()), check(configSet(rootPath = "config/sub")))
    }

    @Test
    fun `any of an instance's trusted host keys is accepted, not only the first`() {
        val configSet = configSet(credential = authorized(credentials.create("multi-key")))

        assertEquals(SourceCheck.Passed(commits.last()), check(configSet))
    }

    @Test
    fun `a credential whose Git instance is no longer configured is refused`() {
        val configSet = configSet()
        jdbc
            .sql("update credential set git_instance = 'removed' where id = :id")
            .param("id", credential.id.value)
            .update()

        assertFailsWith<GitInstanceNotConfiguredException> { check(configSet) }
    }

    @Test
    fun `a host key that is not trusted fails the check and nothing is fetched`() {
        val configSet = configSet(credential = credentials.create("wrong-host-key"))

        assertEquals(SourceCheck.Failed(SourceFailure.HOST_KEY_REJECTED), check(configSet))
        assertFalse(cacheOf(configSet).exists())
    }

    @Test
    fun `known hosts, keys and config in the SSH home directory are ignored`() {
        val ssh = properties.cacheDirectory.resolve("ssh-home/.ssh").createDirectories()
        val (privateKey, publicKey) = SshGitServer.newIdentity()
        try {
            ssh.resolve("known_hosts").writeText("${SshGitServer.knownHostsName} ${SshGitServer.hostKey}\n")
            ssh.resolve("id_ed25519").writeText(privateKey)
            Files.setPosixFilePermissions(ssh.resolve("id_ed25519"), PosixFilePermissions.fromString("rw-------"))
            ssh.resolve("config").writeText("Host *\n  StrictHostKeyChecking no\n")
            SshGitServer.revokeAll()
            SshGitServer.authorize(publicKey)

            // The real host key is only in known_hosts, and the only authorized key is only in ~/.ssh.
            val wrongHostKey = configSet(credential = credentials.create("wrong-host-key"))
            assertEquals(SourceCheck.Failed(SourceFailure.HOST_KEY_REJECTED), check(wrongHostKey))
            assertEquals(SourceCheck.Failed(SourceFailure.AUTH_FAILED), check(configSet()))
        } finally {
            listOf("known_hosts", "id_ed25519", "config").forEach { ssh.resolve(it).deleteIfExists() }
        }
    }

    @Test
    fun `a key the server does not accept fails as an authentication failure`() {
        SshGitServer.revokeAll()

        assertEquals(SourceCheck.Failed(SourceFailure.AUTH_FAILED), check(configSet()))
    }

    @Test
    fun `a missing repository, branch or root path fails with its own code`() {
        assertEquals(
            SourceCheck.Failed(SourceFailure.REPOSITORY_NOT_FOUND),
            check(configSet(repositoryPath = "repos/missing.git")),
        )
        assertEquals(SourceCheck.Failed(SourceFailure.BRANCH_NOT_FOUND), check(configSet(branch = "missing")))
        assertEquals(SourceCheck.Failed(SourceFailure.ROOT_PATH_NOT_FOUND), check(configSet(rootPath = "missing")))
        // A file is not a root path.
        assertEquals(
            SourceCheck.Failed(SourceFailure.ROOT_PATH_NOT_FOUND),
            check(configSet(rootPath = "config/app.yaml")),
        )
    }

    @Test
    fun `an unreachable server fails as unreachable`() {
        assertEquals(
            SourceCheck.Failed(SourceFailure.UNREACHABLE),
            check(configSet(credential = credentials.create("unreachable"))),
        )
    }

    @Test
    fun `a pending key can be verified before activation while only it is authorized`() {
        val pending = credentials.regenerate(credential.id).keys.single { it.status == KeyStatus.PENDING }
        SshGitServer.revokeAll()
        SshGitServer.authorize(pending.publicKey)
        val configSet = configSet()

        assertEquals(SourceCheck.Passed(commits.last()), configSets.check(configSet.id, pending.id))
        assertEquals(SourceCheck.Failed(SourceFailure.AUTH_FAILED), check(configSet))

        credentials.activate(credential.id, pending.id)

        assertEquals(SourceCheck.Passed(commits.last()), check(configSet))
    }

    @Test
    fun `verifying a pending key the server does not accept fails while the active key still works`() {
        val pending = credentials.regenerate(credential.id).keys.single { it.status == KeyStatus.PENDING }
        val configSet = configSet()

        assertEquals(SourceCheck.Failed(SourceFailure.AUTH_FAILED), configSets.check(configSet.id, pending.id))
        assertEquals(SourceCheck.Passed(commits.last()), check(configSet))
    }

    @Test
    fun `a disabled credential is refused before any connection`() {
        val configSet = configSet()
        credentials.disable(credential.id)

        assertFailsWith<CredentialDisabledException> { check(configSet) }
    }

    @Test
    fun `fetch, then list and read beneath the root path at the tip and at an older commit`() {
        val configSet = configSet()
        val (first, second) = commits

        assertEquals(second, sources.fetch(id(configSet), configSet.source))

        assertEquals(listOf("app.yaml", "sub/extra.json"), list(configSet, RevisionRef.Latest))
        assertEquals(listOf("app.yaml"), list(configSet, RevisionRef.Commit(first)))
        assertEquals("v2", read(configSet, "app.yaml", RevisionRef.Latest))
        assertEquals("v2", read(configSet, "app.yaml", RevisionRef.Commit(second)))
        assertEquals("v1", read(configSet, "app.yaml", RevisionRef.Commit(first)))
        assertEquals("{}", read(configSet, "sub/extra.json", RevisionRef.Latest))
    }

    @Test
    fun `a second fetch moves the tip to new commits`() {
        val configSet = configSet()
        sources.fetch(id(configSet), configSet.source)

        val third = SshGitServer.addCommit("configs", "v3")

        assertEquals(third, sources.fetch(id(configSet), configSet.source))
        assertEquals("v3", read(configSet, "app.yaml", RevisionRef.Latest))
        assertEquals("v2", read(configSet, "app.yaml", RevisionRef.Commit(commits.last())))
    }

    @Test
    fun `a failure before the fetch leaves a good cache intact`() {
        val configSet = configSet()
        sources.fetch(id(configSet), configSet.source)
        SshGitServer.revokeAll()

        assertEquals(SourceCheck.Failed(SourceFailure.AUTH_FAILED), check(configSet))
        assertEquals("v2", read(configSet, "app.yaml", RevisionRef.Latest))
    }

    @Test
    fun `reads cannot leave the root path and serve regular files only`() {
        val configSet = configSet()
        sources.fetch(id(configSet), configSet.source)

        listOf("../README.md", "/README.md", "sub/../../README.md").forEach {
            assertFailsWith<InvalidSourcePathException> { read(configSet, it, RevisionRef.Latest) }
        }
        // The root path is config/, so README.md beneath it does not exist; the symlink and directories are no files.
        listOf("README.md", "link", "sub", "missing.yaml").forEach {
            assertFailsWith<SourceFileNotFoundException> { read(configSet, it, RevisionRef.Latest) }
        }

        // At the repository root, the empty path is the root directory itself.
        val atRoot = configSet(rootPath = "")
        sources.fetch(id(atRoot), atRoot.source)
        assertFailsWith<SourceFileNotFoundException> { read(atRoot, "", RevisionRef.Latest) }
        assertEquals("readme", read(atRoot, "README.md", RevisionRef.Latest))
    }

    @Test
    fun `reads before a fetch or at a commit the cache lacks are not found`() {
        val configSet = configSet()

        assertFailsWith<RevisionNotFoundException> { list(configSet, RevisionRef.Latest) }
        sources.fetch(id(configSet), configSet.source)
        assertFailsWith<RevisionNotFoundException> { list(configSet, RevisionRef.Commit("0".repeat(40))) }
    }

    @Test
    fun `a corrupt cache is replaced on the next fetch`() {
        val configSet = configSet()
        sources.fetch(id(configSet), configSet.source)
        cacheOf(configSet).resolve("objects").toFile().deleteRecursively()

        assertEquals(commits.last(), sources.fetch(id(configSet), configSet.source))
        assertEquals("v2", read(configSet, "app.yaml", RevisionRef.Latest))

        // A file where the repository should be.
        cacheOf(configSet).toFile().deleteRecursively()
        Files.writeString(cacheOf(configSet), "not a repository")

        assertEquals(commits.last(), sources.fetch(id(configSet), configSet.source))
        assertEquals("v1", read(configSet, "app.yaml", RevisionRef.Commit(commits.first())))
    }

    private fun check(configSet: ConfigSet) = configSets.check(configSet.id, null)

    private fun configSet(
        credential: Credential = this.credential,
        repositoryPath: String = "repos/configs.git",
        branch: String = "main",
        rootPath: String = "config",
    ): ConfigSet {
        val source =
            SourceDefinition(credential.id, RepositoryPath(repositoryPath), Branch(branch), SourcePath.parse(rootPath))
        return configSets.create(namespace.id, Slug("cfg-${System.nanoTime()}"), source)
    }

    private fun list(
        configSet: ConfigSet,
        revision: RevisionRef,
    ) = sources.list(id(configSet), configSet.source, revision).map { it.value }

    private fun read(
        configSet: ConfigSet,
        path: String,
        revision: RevisionRef,
    ) = String(sources.read(id(configSet), configSet.source, SourcePath.parse(path), revision))

    private fun id(configSet: ConfigSet) = configSet.id.value

    private fun cacheOf(configSet: ConfigSet): Path = properties.cacheDirectory.resolve("${id(configSet)}.git")

    private fun authorized(credential: Credential): Credential {
        SshGitServer.authorize(credential.key(KeyStatus.ACTIVE))
        return credential
    }

    private fun Credential.key(status: KeyStatus) = keys.single { it.status == status }.publicKey

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun gitServer(registry: DynamicPropertyRegistry) {
            SshGitServer.register(registry)
        }
    }
}
