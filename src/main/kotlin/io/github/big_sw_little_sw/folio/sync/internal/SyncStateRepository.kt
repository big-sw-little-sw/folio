package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.sync.SyncState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A claim on one ConfigSet's sync (ADR 0032). [owner] and [until] together identify it: a lease that expired and was
 * claimed again, even by the same instance, has a later [until].
 */
data class SyncLease(
    val configSetId: ConfigSetId,
    val owner: UUID,
    val until: OffsetDateTime,
    val consecutiveFailures: Int,
)

/**
 * The `sync_state` table. Times come from the database clock, so that instances with skewed clocks agree on when
 * leases expire and ConfigSets are due.
 */
@Repository
class SyncStateRepository(
    private val jdbc: JdbcClient,
) {
    /** A new ConfigSet's state: never synced, due now. */
    fun insert(id: ConfigSetId) {
        jdbc
            .sql("insert into sync_state (config_set_id, next_due_at) values (:id, now())")
            .param("id", id.value)
            .update()
    }

    fun find(id: ConfigSetId): SyncState? =
        jdbc
            .sql("select $COLUMNS from sync_state where config_set_id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.toSyncState() }
            .optional()
            .orElse(null)

    /** Makes the ConfigSet due now unless it already is; null if it has no state. */
    fun markDue(id: ConfigSetId): SyncState? =
        jdbc
            .sql(
                """
                update sync_state set next_due_at = least(next_due_at, now()) where config_set_id = :id
                returning $COLUMNS
                """.trimIndent(),
            ).param("id", id.value)
            .query { rs, _ -> rs.toSyncState() }
            .optional()
            .orElse(null)

    /**
     * Leases up to [limit] due ConfigSets to [owner] for [duration], most overdue first. A ConfigSet is due when its
     * next due time has come and it is not leased, or its lease has expired. Rows that another claim has locked are
     * skipped, so concurrent claims never return the same ConfigSet. [excluding] are never claimed.
     */
    fun claimDue(
        owner: UUID,
        duration: Duration,
        limit: Int,
        excluding: Set<ConfigSetId>,
    ): List<SyncLease> {
        // An empty `not in ()` is not SQL, so the condition is left out rather than given a placeholder ID.
        val exclusion = if (excluding.isEmpty()) "" else "and config_set_id not in (:excluding)"
        val params =
            mapOf("owner" to owner, "leaseMillis" to duration.toMillis(), "limit" to limit) +
                if (excluding.isEmpty()) emptyMap() else mapOf("excluding" to excluding.map { it.value })
        // LEASE_END appears twice; now() is fixed for the statement, so both give the same time.
        return jdbc
            .sql(
                """
                update sync_state s
                set lease_owner = :owner, lease_until = $LEASE_END, next_due_at = $LEASE_END
                from (
                    select config_set_id from sync_state
                    where next_due_at <= now() and (lease_until is null or lease_until < now()) $exclusion
                    order by next_due_at
                    limit :limit
                    for update skip locked
                ) due
                where s.config_set_id = due.config_set_id
                returning s.config_set_id, s.lease_owner, s.lease_until, s.consecutive_failures
                """.trimIndent(),
            ).params(params)
            .query { rs, _ -> rs.toLease() }
            .list()
    }

    private fun ResultSet.toLease() =
        SyncLease(
            ConfigSetId(getObject("config_set_id", UUID::class.java)),
            getObject("lease_owner", UUID::class.java),
            getObject("lease_until", OffsetDateTime::class.java),
            getInt("consecutive_failures"),
        )

    /**
     * Records a successful fetch whose branch tip is [revision], and releases the lease. An unchanged tip leaves the
     * revisions as they were; a new one becomes the synced revision and is added to `synced_revision`, or moved to the
     * top if it was synced before (ADR 0036). One statement, so the two never disagree. Returns the state it replaced,
     * or null, recording nothing, if [lease] is no longer held.
     */
    fun recordSuccess(
        lease: SyncLease,
        revision: String,
        delay: Duration,
    ): PreviousSync? =
        jdbc
            .sql(
                """
                with recorded as (
                    update sync_state
                    set last_seen_revision = :revision, last_synced_revision = :revision,
                        last_attempt_at = now(), last_success_at = now(),
                        last_error_code = null, last_error_summary = null, consecutive_failures = 0,
                        $RELEASE
                    returning config_set_id, $PREVIOUS
                ), revision as (
                    insert into synced_revision (config_set_id, commit_id, synced_at)
                    select config_set_id, :revision, now() from recorded
                    where :revision is distinct from recorded.previous_revision
                    on conflict (config_set_id, commit_id) do update set synced_at = excluded.synced_at
                )
                select previous_revision, previous_error_code from recorded
                """.trimIndent(),
            ).param("revision", revision)
            .params(releaseParams(lease, delay))
            .query { rs, _ -> rs.toPreviousSync() }
            .optional()
            .orElse(null)

    /**
     * Records a failed attempt, keeping the revisions, and releases the lease. Returns the state it replaced, or null,
     * recording nothing, if [lease] is no longer held.
     */
    fun recordFailure(
        lease: SyncLease,
        failure: SourceFailure,
        delay: Duration,
    ): PreviousSync? =
        jdbc
            .sql(
                """
                update sync_state
                set last_attempt_at = now(), last_error_code = :code, last_error_summary = :summary,
                    consecutive_failures = consecutive_failures + 1,
                    $RELEASE
                returning $PREVIOUS
                """.trimIndent(),
            ).param("code", failure.name)
            .param("summary", failure.summary)
            .params(releaseParams(lease, delay))
            .query { rs, _ -> rs.toPreviousSync() }
            .optional()
            .orElse(null)

    private fun ResultSet.toPreviousSync() =
        PreviousSync(getString("previous_revision"), getString("previous_error_code"))

    /**
     * Releases the lease without recording an attempt, so the ConfigSet is due after [delay]. Returns false if [lease]
     * is no longer held.
     */
    fun release(
        lease: SyncLease,
        delay: Duration,
    ): Boolean =
        jdbc
            .sql("update sync_state set $RELEASE")
            .params(releaseParams(lease, delay))
            .update() == 1

    /** Which of [ids] are ConfigSets; every ConfigSet has a state, and deleting it deletes the state. */
    fun existing(ids: Set<UUID>): Set<UUID> =
        jdbc
            .sql("select config_set_id from sync_state where config_set_id in (:ids)")
            .param("ids", ids)
            .query { rs, _ -> rs.getObject("config_set_id", UUID::class.java) }
            .set()

    private fun releaseParams(
        lease: SyncLease,
        delay: Duration,
    ) = mapOf(
        "id" to lease.configSetId.value,
        "owner" to lease.owner,
        "until" to lease.until,
        "delayMillis" to delay.toMillis(),
    )

    private fun ResultSet.toSyncState() =
        SyncState(
            configSetId = ConfigSetId(getObject("config_set_id", UUID::class.java)),
            lastSeenRevision = getString("last_seen_revision"),
            lastSyncedRevision = getString("last_synced_revision"),
            lastAttemptAt = instant("last_attempt_at"),
            lastSuccessAt = instant("last_success_at"),
            errorCode = getString("last_error_code"),
            errorSummary = getString("last_error_summary"),
            consecutiveFailures = getInt("consecutive_failures"),
            nextDueAt = checkNotNull(instant("next_due_at")),
        )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private companion object {
        const val COLUMNS =
            "config_set_id, last_seen_revision, last_synced_revision, last_attempt_at, last_success_at, " +
                "last_error_code, last_error_summary, consecutive_failures, next_due_at"

        const val LEASE_END = "now() + :leaseMillis * interval '1 millisecond'"

        /** The row as it was before a recording's update (PostgreSQL 18's `old` in `returning`). */
        const val PREVIOUS = "old.last_synced_revision as previous_revision, old.last_error_code as previous_error_code"

        /**
         * Releases the lease if it is still held. A manual request during the sync moved next_due_at before the
         * lease end, and then stays, so the ConfigSet syncs again at once; otherwise it is due after `:delayMillis`.
         */
        const val RELEASE = """next_due_at = case when next_due_at = lease_until
                        then now() + :delayMillis * interval '1 millisecond' else next_due_at end,
                    lease_owner = null, lease_until = null
                where config_set_id = :id and lease_owner = :owner and lease_until = :until"""
    }
}
