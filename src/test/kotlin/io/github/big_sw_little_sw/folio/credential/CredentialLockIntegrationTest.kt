package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceService
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.credential.internal.CredentialService
import io.github.big_sw_little_sw.folio.credential.internal.KeyStatus
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.*

/**
 * These tests check the locks of credential writes and their races with catalog writes. Each test holds a lock in a
 * transaction while the writes start, so the writes wait and the result does not depend on timing.
 */
@FolioIntegrationTest
class CredentialLockIntegrationTest(
    @Autowired private val credentials: CredentialService,
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val tree: TreeRepository,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val fixture: TestCredentials,
    @Autowired private val audit: TestAudit,
    @Autowired private val locks: TestLocks,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `parallel regenerations add one pending key`() {
        val credential = fixture.credential().id

        val results = whileRowLocked(credential, *Array(4) { { credentials.regenerate(SUPER_ADMIN, credential) } })

        assertEquals(1, results.count { it.isSuccess }, results.toString())
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is CredentialException.PendingKeyExists })
        assertEquals(1, keyCount(credential, KeyStatus.PENDING))
    }

    @Test
    fun `parallel activations of one pending key activate it one time`() {
        val credential = fixture.credential().id
        val pending = fixture.pendingKey(credential).id

        val results =
            whileRowLocked(
                credential,
                { credentials.activate(SUPER_ADMIN, credential, pending) },
                { credentials.activate(SUPER_ADMIN, credential, pending) },
            )

        assertEquals(1, results.count { it.isSuccess }, results.toString())
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is CredentialException.KeyNotPending)
        assertEquals(1, keyCount(credential, KeyStatus.ACTIVE))
        assertEquals(1, audit.records(credential.value).count { it["operation"] == "CREDENTIAL_ACTIVATED" })
    }

    /**
     * A credential write authorizes at `READ COMMITTED`, so Access takes the shared tree lock. Thus the write waits for
     * a catalog write that holds the tree lock. [TestLocks] fails the test if the write does not wait.
     */
    @Test
    fun `a credential write waits for a catalog write`() {
        val credential = fixture.credential().id

        val results = locks.whileHeld({ tree.lock() }, { credentials.regenerate(SUPER_ADMIN, credential) })

        assertTrue(results.single().isSuccess, results.toString())
        assertEquals(1, keyCount(credential, KeyStatus.PENDING))
    }

    /**
     * Onboarding holds the exclusive tree lock and then inserts a `git_source` row, which waits for the credential row
     * at the foreign key. The holder does the same, but inserts only after the delete waits. The delete takes the tree
     * lock before the row lock (design 8.1), so the insert does not wait for it, and no deadlock occurs.
     */
    @Test
    fun `a credential delete waits for an onboarding that holds the tree lock, and the credential stays in use`() {
        val credential = fixture.credential().id
        val configSet = catalog.configSet(catalog.namespace())

        val results =
            locks.whileHeld(
                { tree.lock() },
                { credentials.delete(SUPER_ADMIN, credential) },
                beforeCommit = {
                    jdbc
                        .sql(
                            """
                            insert into git_source (config_set_id, credential_id, repository_path, branch, root_path)
                            values (:configSetId, :credentialId, 'org/repo.git', 'main', '')
                            """.trimIndent(),
                        ).param("configSetId", configSet.value)
                        .param("credentialId", credential.value)
                        .update()
                },
            )

        assertTrue(results.single().exceptionOrNull() is CredentialException.CredentialInUse, results.toString())
    }

    /**
     * The holder inserts a credential and does not commit. The emptiness query of the delete does not see that row, so
     * the delete waits for the holder at the foreign key. After the commit, the foreign key stops the delete.
     */
    @Test
    fun `a credential that a parallel transaction adds stops a Namespace delete`() {
        val namespace = catalog.namespace()

        val results =
            locks.whileHeld(
                {
                    jdbc
                        .sql(
                            """
                            insert into credential (namespace_id, name, git_instance, status)
                            values (:namespaceId, 'raced', 'github', 'ENABLED')
                            """.trimIndent(),
                        ).param("namespaceId", namespace.value)
                        .update()
                },
                { namespaces.delete(SUPER_ADMIN, namespace) },
            )

        assertTrue(results.single().exceptionOrNull() is CatalogException.NamespaceNotEmpty, results.toString())
        assertEquals(1, count("select count(*) from namespace where id = :id", namespace))
        assertEquals(1, count("select count(*) from credential where namespace_id = :id", namespace))
    }

    /**
     * The holder deletes the Namespace with plain SQL, so it takes no tree lock, and it does not commit. The create
     * still finds the Namespace, and its insert waits for the holder at the foreign key. After the commit, the foreign
     * key stops the insert.
     */
    @Test
    fun `a credential create after a parallel Namespace delete finds no Namespace`() {
        val namespace = catalog.namespace()

        val results =
            locks.whileHeld(
                { jdbc.sql("delete from namespace where id = :id").param("id", namespace.value).update() },
                { credentials.create(SUPER_ADMIN, namespace, Slug("raced"), Slug("github")) },
            )

        assertTrue(results.single().exceptionOrNull() is CatalogException.NamespaceNotFound, results.toString())
        assertEquals(0, count("select count(*) from credential where namespace_id = :id", namespace))
    }

    /** Runs [changes] in parallel while another transaction holds the row lock of the credential. */
    private fun whileRowLocked(credential: CredentialId, vararg changes: () -> Any): List<Result<Any?>> =
        locks.whileHeld(
            {
                jdbc
                    .sql("select id from credential where id = :id for update")
                    .param("id", credential.value)
                    .query()
                    .listOfRows()
            },
            *changes,
        )

    private fun keyCount(credential: CredentialId, status: KeyStatus): Int = jdbc
        .sql("select count(*) from credential_key where credential_id = :id and status = :status")
        .param("id", credential.value)
        .param("status", status.name)
        .query(Int::class.java)
        .single()

    private fun count(sql: String, namespace: NamespaceId): Int = jdbc
        .sql(sql)
        .param("id", namespace.value)
        .query(Int::class.java)
        .single()
}
