package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.sync.SyncedRevision
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** Reads of the `synced_revision` table; [SyncStateRepository.recordSuccess] writes it (ADR 0036). */
@Repository
class SyncedRevisionRepository(
    private val jdbc: JdbcClient,
) {
    fun exists(
        id: ConfigSetId,
        commitId: String,
    ): Boolean =
        jdbc
            .sql("select exists (select 1 from synced_revision where config_set_id = :id and commit_id = :commitId)")
            .param("id", id.value)
            .param("commitId", commitId)
            .query(Boolean::class.java)
            .single()

    /** At most [limit] revisions, newest first. */
    fun newest(
        id: ConfigSetId,
        limit: Int,
    ): List<SyncedRevision> =
        jdbc
            .sql(
                """
                select commit_id, synced_at from synced_revision where config_set_id = :id
                order by synced_at desc, commit_id
                limit :limit
                """.trimIndent(),
            ).param("id", id.value)
            .param("limit", limit)
            .query { rs, _ ->
                SyncedRevision(
                    rs.getString("commit_id"),
                    rs.getObject("synced_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
}
