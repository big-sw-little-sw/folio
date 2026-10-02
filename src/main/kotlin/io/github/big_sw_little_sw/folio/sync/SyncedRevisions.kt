package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.sync.internal.SyncStateRepository
import io.github.big_sw_little_sw.folio.sync.internal.SyncedRevisionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** A commit that became a ConfigSet's synced revision, and when it last did (ADR 0036). */
data class SyncedRevision(
    val commitId: String,
    val syncedAt: Instant,
)

/**
 * The revisions Folio synced, which are the only ones consumers may read (ADR 0036). Nothing here authorizes; the
 * consumption API does, as for `ConfigSetSources`.
 */
@Service
class SyncedRevisions(
    private val states: SyncStateRepository,
    private val revisions: SyncedRevisionRepository,
) {
    /** The revision served as latest: the last synced one. Null if the ConfigSet never synced or does not exist. */
    @Transactional(readOnly = true)
    fun latest(id: ConfigSetId): String? = states.find(id)?.lastSyncedRevision

    /** The source failure code of the most recent sync attempt if it failed, otherwise null. */
    @Transactional(readOnly = true)
    fun lastErrorCode(id: ConfigSetId): String? = states.find(id)?.errorCode

    /** Whether [commitId] was ever the synced revision of [id]. */
    @Transactional(readOnly = true)
    fun contains(
        id: ConfigSetId,
        commitId: String,
    ): Boolean = revisions.exists(id, commitId)

    /** At most [limit] synced revisions of [id], newest first; the first is the latest. */
    @Transactional(readOnly = true)
    fun newest(
        id: ConfigSetId,
        limit: Int,
    ): List<SyncedRevision> = revisions.newest(id, limit)
}
