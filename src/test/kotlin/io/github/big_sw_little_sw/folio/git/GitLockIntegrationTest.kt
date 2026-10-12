package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.git.internal.Branch
import io.github.big_sw_little_sw.folio.git.internal.GitSourceDefinition
import io.github.big_sw_little_sw.folio.git.internal.GitSourceService
import io.github.big_sw_little_sw.folio.git.internal.RepositoryPath
import io.github.big_sw_little_sw.folio.git.internal.RootPath
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.*

/**
 * These tests check the lock order of onboarding. Each test holds a lock in a transaction while the writes start, so
 * the writes wait and the result does not depend on timing.
 */
@GitIntegrationTest
class GitLockIntegrationTest(
    @Autowired private val sources: GitSourceService,
    @Autowired private val tree: TreeRepository,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val credentials: TestCredentials,
    @Autowired private val locks: TestLocks,
    @Autowired private val jdbc: JdbcClient,
) {
    /**
     * Each onboarding takes the exclusive tree lock before `Access` takes the shared form. In the other order, both
     * would get the shared form after the holder commits, and each would wait for the exclusive form of the other.
     */
    @Test
    fun `two onboardings that wait for the tree lock both succeed`() {
        val credential = credentials.credential().id
        val (first, second) = catalog.namespace() to catalog.namespace()

        val results = locks.whileHeld({ tree.lock() }, { create(first, credential) }, { create(second, credential) })

        assertTrue(results.all { it.isSuccess }, results.toString())
        assertEquals(2, count("select count(*) from git_source where credential_id = :id", credential.value))
    }

    /**
     * The holder deletes the credential through the credential module and does not commit. The delete holds the shared
     * tree lock, so the onboarding waits for the exclusive form. After the commit, the onboarding finds no credential,
     * and its ConfigSet rolls back.
     */
    @Test
    fun `an onboarding after a parallel credential delete finds no credential`() {
        val credential = credentials.credential().id
        val namespace = catalog.namespace()

        val results = locks.whileHeld({ credentials.delete(credential) }, { create(namespace, credential, "raced") })

        assertTrue(results.single().exceptionOrNull() is CredentialException.CredentialNotFound, results.toString())
        assertEquals(0, count("select count(*) from config_set where namespace_id = :id", namespace.value))
    }

    private fun create(namespace: NamespaceId, credential: CredentialId, slug: String = TestCatalog.uniqueSlug()) =
        sources.create(
            SUPER_ADMIN,
            namespace,
            Slug(slug),
            null,
            GitSourceDefinition(credential, RepositoryPath("org/repo.git"), Branch("main"), RootPath("")),
        )

    private fun count(sql: String, id: UUID): Int = jdbc
        .sql(sql)
        .param("id", id)
        .query(Int::class.java)
        .single()
}
