package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.testing.GitIntegrationTest
import io.github.big_sw_little_sw.testing.SshGitServer
import io.github.big_sw_little_sw.testing.TestAudit
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestGit
import io.github.big_sw_little_sw.testing.TestSyncInstances
import io.github.big_sw_little_sw.testing.bearer
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests check the leases of sync across instances (design 10.6). Each [Synchronizer] is one instance with its
 * own lease owner. Each test first makes all Git sources not due, so a claim takes only the ConfigSets of the test.
 */
@GitIntegrationTest
class SyncLeaseIntegrationTest(
    @Autowired private val synchronizer: Synchronizer,
    @Autowired private val git: TestGit,
    @Autowired private val audit: TestAudit,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val syncs: SyncRepository,
    @Autowired private val mvc: MockMvc,
    @Autowired instances: TestSyncInstances,
) {
    private val other = instances.synchronizer()
    private lateinit var commits: List<String>

    @BeforeEach
    fun setUp() {
        SshGitServer.revokeAll()
        commits = SshGitServer.createRepository("configs")
        git.parkAll()
    }

    @Test
    fun `two instances that claim at the same time never claim the same ConfigSet`() {
        val credential = git.authorizedCredential().id
        val configSets = (1..CONFIG_SETS).map { git.configSet(credential) }.toSet()
        val start = CountDownLatch(1)

        val claims =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val futures =
                    listOf(synchronizer, other).map { instance ->
                        executor.submit<Set<ConfigSetId>> {
                            start.await()
                            instance.claim(CONFIG_SETS).map { it.configSet }.toSet()
                        }
                    }
                start.countDown()
                futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }

        assertEquals(emptySet(), claims[0] intersect claims[1])
        assertEquals(configSets, claims[0] + claims[1])
        assertEquals(emptyList(), synchronizer.claim(CONFIG_SETS))
    }

    /**
     * A transaction locks the row of one Git source, as the claim of another instance does. The claim skips the row and
     * does not wait for it. After the commit, the next claim takes it.
     */
    @Test
    fun `a claim skips a Git source that another claim has locked`() {
        val credential = git.authorizedCredential().id
        val locked = git.configSet(credential)
        val free = git.configSet(credential)
        val held = CountDownLatch(1)
        val claimed = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val holder =
                executor.submit {
                    transactions.executeWithoutResult {
                        jdbc
                            .sql("select 1 from git_source where config_set_id = :id for update")
                            .param("id", locked.value)
                            .query()
                            .listOfRows()
                        held.countDown()
                        claimed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                }
            try {
                assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(listOf(free), other.claim(CONFIG_SETS).map { it.configSet })
            } finally {
                claimed.countDown()
                holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        assertEquals(listOf(locked), other.claim(CONFIG_SETS).map { it.configSet })
    }

    @Test
    fun `a lease lasts the fetch deadline and three minutes, and the ConfigSet is due again at its end`() {
        val configSet = git.configSet(git.authorizedCredential().id)

        val lease = synchronizer.claim(1).single()

        assertEquals(synchronizer.owner, git.leaseOwner(configSet))
        assertTrue(git.state(configSet).syncing)
        // The claim sets the lease end with the database clock, so the test reads the length with the same clock.
        val (length, dueAtEnd) =
            jdbc
                .sql(
                    """
                    select extract(epoch from lease_end - now())::float8 as length, next_due_at = lease_end as due_at_end
                    from git_source where config_set_id = :id
                    """.trimIndent(),
                ).param("id", configSet.value)
                .query { rs, _ -> rs.getDouble("length") to rs.getBoolean("due_at_end") }
                .single()
        assertTrue(length > LEASE_SECONDS - MARGIN_SECONDS && length <= LEASE_SECONDS, "$length s")
        assertTrue(dueAtEnd)
        assertEquals(emptyList(), other.claim(1))
        assertTrue(synchronizer.sync(lease))
    }

    @Test
    fun `an instance takes over an ended lease, and the earlier owner records nothing`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        val lost = synchronizer.claim(1).single()
        git.expireLease(configSet)
        val taken = other.claim(1).single()

        assertFalse(synchronizer.sync(lost))
        assertNull(git.state(configSet).lastAttemptAt)
        assertEquals(other.owner, git.leaseOwner(configSet))
        assertEquals(emptyList(), syncRecords(configSet))

        assertTrue(other.sync(taken))
        assertEquals(commits.last(), git.state(configSet).lastSeenCommit)
        assertNull(git.leaseOwner(configSet))
        assertEquals(listOf("SYNC_REVISION_PUBLISHED"), syncRecords(configSet))
    }

    @Test
    fun `a lease that the same instance claims again replaces the earlier lease`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        val earlier = synchronizer.claim(1).single()
        git.expireLease(configSet)
        val later = synchronizer.claim(1).single()

        assertFalse(synchronizer.sync(earlier))
        assertTrue(synchronizer.sync(later))
    }

    @Test
    fun `a claim never takes the ConfigSets that it must leave out`() {
        val credential = git.authorizedCredential().id
        val left = git.configSet(credential)
        val taken = git.configSet(credential)

        assertEquals(listOf(taken), synchronizer.claim(CONFIG_SETS, setOf(left)).map { it.configSet })
    }

    /** A manual sync during a lease moves the due time before the lease end. The release keeps that due time. */
    @Test
    fun `a manual sync during a sync makes the ConfigSet due again when the sync ends`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        val lease = synchronizer.claim(1).single()

        syncs.requestSync(configSet)
        assertEquals(emptyList(), other.claim(1))
        assertTrue(synchronizer.sync(lease))

        assertEquals(listOf(configSet), other.claim(1).map { it.configSet })
    }

    @Test
    fun `a release without an attempt makes the ConfigSet due after the delay and records nothing`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        val lease = synchronizer.claim(1).single()

        synchronizer.release(lease, Duration.ZERO)

        assertNull(git.leaseOwner(configSet))
        assertNull(git.state(configSet).lastAttemptAt)
        assertEquals(listOf(configSet), other.claim(1).map { it.configSet })
    }

    /**
     * The record update locks the row of the Git source, so a delete of the ConfigSet commits before the record or
     * after it. Here it commits first, and the record finds no row.
     */
    @Test
    fun `a sync of a ConfigSet that a delete removed during the sync records nothing`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        val lease = synchronizer.claim(1).single()

        mvc.delete("/api/v1/admin/configsets/${configSet.toApiId()}") { bearer(SUPER_ADMIN) }.andExpect {
            status { isNoContent() }
        }

        assertFalse(synchronizer.sync(lease))
        assertEquals(emptyList(), syncRecords(configSet))
        val revisions =
            jdbc
                .sql("select count(*) from git_revision where config_set_id = :id")
                .param("id", configSet.value)
                .query(Long::class.java)
                .single()
        assertEquals(0L, revisions)
    }

    private fun syncRecords(configSet: ConfigSetId) =
        audit.records(configSet.value).map { it["operation"] as String }.filter { it.startsWith("SYNC_") }

    private companion object {
        const val CONFIG_SETS = 6
        const val TIMEOUT_SECONDS = 20L

        /** The default fetch deadline of 5 minutes and the margin of 3 minutes. */
        const val LEASE_SECONDS = 8 * 60L
        const val MARGIN_SECONDS = 5L
    }
}
