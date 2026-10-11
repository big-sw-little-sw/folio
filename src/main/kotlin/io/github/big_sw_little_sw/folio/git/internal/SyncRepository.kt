package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import org.eclipse.jgit.lib.ObjectId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** The lease owner of one sync instance (design 10.6). Each start of the process gets a new owner. */
@JvmInline
value class LeaseOwner(val value: UUID)

/**
 * A claim on the sync of one ConfigSet (design 10.6). [owner] and [end] together identify the claim. A new claim after
 * the end of a lease has a later [end], also when the same instance makes the claim. Thus a sync with the old lease
 * records nothing. The claim also gives what the sync needs: the Source definition, the number of consecutive failures
 * and the last published commit.
 */
data class SyncLease(
    val configSet: ConfigSetId,
    val owner: LeaseOwner,
    val end: OffsetDateTime,
    val consecutiveFailures: Int,
    val source: GitSourceDefinition,
    val lastPublished: ObjectId?,
)

/**
 * The sync state of a Git source (design 10.6). [failure] is the code of the last attempt, or null after a success.
 * [syncing] is true while an instance holds a lease.
 */
data class SyncState(
    val lastSeenCommit: String?,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val failure: FailureCode?,
    val consecutiveFailures: Int,
    val nextDueAt: Instant,
    val syncing: Boolean,
)

/**
 * Reads and writes the sync state and the lease columns of `git_source` (design 10.6, 16). All times come from the
 * database clock, so instances with different clocks agree on due times and leases.
 *
 * A write of a result or a release changes the row only if the lease still belongs to the claim. Thus an instance
 * whose lease ended and that another instance claimed records nothing.
 */
@Repository
class SyncRepository(private val jdbc: JdbcClient) {
    /**
     * Claims a maximum of [limit] due ConfigSets for [owner] for [length], the most overdue first. A ConfigSet is due
     * when its due time has come and no lease holds it. The statement skips rows that another claim has locked, so two
     * claims never get the same ConfigSet. The claim never takes the ConfigSets in [excluding].
     */
    fun claim(owner: LeaseOwner, length: Duration, limit: Int, excluding: Set<ConfigSetId>): List<SyncLease> {
        // An empty "not in ()" is not valid SQL, so the statement leaves out the condition.
        val exclusion = if (excluding.isEmpty()) "" else "and config_set_id not in (:excluding)"
        val parameters =
            mapOf("owner" to owner.value, "lengthMillis" to length.toMillis(), "limit" to limit) +
                if (excluding.isEmpty()) emptyMap() else mapOf("excluding" to excluding.map { it.value })
        // now() is the same for the full statement, so the lease end and the due time are equal. Thus the ConfigSet
        // is due again when the lease ends.
        return jdbc
            .sql(
                """
                update git_source s
                set lease_owner = :owner, lease_end = $LEASE_END, next_due_at = $LEASE_END
                from (
                    select config_set_id from git_source
                    where next_due_at <= now() and (lease_end is null or lease_end <= now()) $exclusion
                    order by next_due_at
                    limit :limit
                    for update skip locked
                ) due
                where s.config_set_id = due.config_set_id
                returning s.config_set_id, s.lease_owner, s.lease_end, s.consecutive_failures, $SOURCE_COLUMNS,
                    ${latestCommitOf("s.config_set_id")} as last_published
                """.trimIndent(),
            ).params(parameters)
            .query { rs, _ -> rs.toLease() }
            .list()
    }

    /**
     * Records a successful attempt that fetched [seen], and releases the lease. The ConfigSet is due after [delay].
     * Returns the previous attempt, or null if the lease no longer belongs to the claim. Then nothing changes.
     */
    fun recordSuccess(lease: SyncLease, seen: ObjectId, delay: Duration): PreviousAttempt? = jdbc
        .sql(
            """
                update git_source
                set last_attempt_at = now(), last_success_at = now(), last_seen_commit = :seen, failure_code = null,
                    consecutive_failures = 0, $RELEASE
                where $LEASE_MATCH
                returning old.failure_code as previous_failure
            """.trimIndent(),
        ).param("seen", seen.name)
        .params(leaseParameters(lease, delay))
        .query { rs, _ -> rs.toPreviousAttempt() }
        .optional()
        .orElse(null)

    /**
     * Records a failed attempt with [code], and releases the lease. A fetch that got the tip [seen] but whose publish
     * step failed changes the last seen commit. The ConfigSet is due after [delay]. Returns the previous attempt, or
     * null if the lease no longer belongs to the claim. Then nothing changes.
     */
    fun recordFailure(lease: SyncLease, code: FailureCode, seen: ObjectId?, delay: Duration): PreviousAttempt? = jdbc
        .sql(
            """
                update git_source
                set last_attempt_at = now(), last_seen_commit = coalesce(cast(:seen as text), last_seen_commit),
                    failure_code = :code, consecutive_failures = consecutive_failures + 1, $RELEASE
                where $LEASE_MATCH
                returning old.failure_code as previous_failure
            """.trimIndent(),
        ).param("seen", seen?.name)
        .param("code", code.name)
        .params(leaseParameters(lease, delay))
        .query { rs, _ -> rs.toPreviousAttempt() }
        .optional()
        .orElse(null)

    /**
     * Releases the lease without a record of an attempt. The ConfigSet is due after [delay]. Returns false if the lease
     * no longer belongs to the claim.
     */
    fun release(lease: SyncLease, delay: Duration): Boolean = jdbc
        .sql("update git_source set $RELEASE where $LEASE_MATCH")
        .params(leaseParameters(lease, delay))
        .update() == 1

    /**
     * Makes the ConfigSet due now (design 10.6). During a lease, the new due time is before the lease end. The release
     * of the lease keeps that due time, so the ConfigSet syncs again at the next poll.
     */
    fun requestSync(configSet: ConfigSetId) {
        jdbc
            .sql("update git_source set next_due_at = least(next_due_at, now()) where config_set_id = :id")
            .param("id", configSet.value)
            .update()
    }

    fun state(configSet: ConfigSetId): SyncState? = jdbc
        .sql(
            """
                select last_seen_commit, last_attempt_at, last_success_at, failure_code, consecutive_failures,
                    next_due_at, coalesce(lease_end > now(), false) as syncing
                from git_source where config_set_id = :id
            """.trimIndent(),
        ).param("id", configSet.value)
        .query { rs, _ -> rs.toState() }
        .optional()
        .orElse(null)

    /** The failure code of the last attempt, or null after a success or before the first attempt. */
    fun failure(configSet: ConfigSetId): FailureCode? = jdbc
        .sql("select failure_code from git_source where config_set_id = :id")
        .param("id", configSet.value)
        .query { rs, _ -> rs.getString("failure_code")?.let(FailureCode::valueOf) }
        .optional()
        .orElse(null)

    /**
     * Returns the ConfigSets of [ids] that exist. Each Git ConfigSet has one `git_source` row, and a delete of the
     * ConfigSet deletes the row (design 10.5).
     */
    fun existing(ids: Set<ConfigSetId>): Set<ConfigSetId> = if (ids.isEmpty()) {
        emptySet()
    } else {
        jdbc
            .sql("select config_set_id from git_source where config_set_id in (:ids)")
            .param("ids", ids.map { it.value })
            .query { rs, _ -> ConfigSetId(rs.getObject("config_set_id", UUID::class.java)) }
            .set()
    }

    private fun leaseParameters(lease: SyncLease, delay: Duration) = mapOf(
        "id" to lease.configSet.value,
        "owner" to lease.owner.value,
        "end" to lease.end,
        "delayMillis" to delay.toMillis(),
    )

    private fun ResultSet.toLease() = SyncLease(
        configSet = ConfigSetId(getObject("config_set_id", UUID::class.java)),
        owner = LeaseOwner(getObject("lease_owner", UUID::class.java)),
        end = getObject("lease_end", OffsetDateTime::class.java),
        consecutiveFailures = getInt("consecutive_failures"),
        source = toSourceDefinition(),
        lastPublished = getString("last_published")?.let(ObjectId::fromString),
    )

    private fun ResultSet.toPreviousAttempt() =
        PreviousAttempt(getString("previous_failure")?.let(FailureCode::valueOf))

    private fun ResultSet.toState() = SyncState(
        lastSeenCommit = getString("last_seen_commit"),
        lastAttemptAt = instant("last_attempt_at"),
        lastSuccessAt = instant("last_success_at"),
        failure = getString("failure_code")?.let(FailureCode::valueOf),
        consecutiveFailures = getInt("consecutive_failures"),
        nextDueAt = checkNotNull(instant("next_due_at")),
        syncing = getBoolean("syncing"),
    )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private companion object {
        const val LEASE_END = "now() + :lengthMillis * interval '1 millisecond'"

        const val LEASE_MATCH = "config_set_id = :id and lease_owner = :owner and lease_end = :end"

        /**
         * Releases the lease. A manual sync during the lease moved the due time before the lease end. That due time
         * stays, so the ConfigSet syncs again at the next poll. Otherwise the ConfigSet is due after `:delayMillis`.
         */
        const val RELEASE =
            "next_due_at = case when next_due_at = lease_end then now() + :delayMillis * interval '1 millisecond' " +
                "else next_due_at end, lease_owner = null, lease_end = null"
    }
}

/**
 * The previous attempt, as the record of a new attempt read it before the update. [failure] is null after a success
 * or before the first attempt.
 */
data class PreviousAttempt(val failure: FailureCode?)
