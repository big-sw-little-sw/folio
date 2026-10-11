package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.content.Revision
import io.github.big_sw_little_sw.folio.content.RevisionId
import io.github.big_sw_little_sw.folio.credential.CredentialId
import org.eclipse.jgit.lib.ObjectId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Reads and writes the `git_source` and `git_revision` rows (design 16). The database is the source of truth for
 * published revisions. The cache only holds their objects (design 10.5).
 */
@Repository
class GitSourceRepository(private val jdbc: JdbcClient) {
    /**
     * Inserts the Source definition. No constraint of `git_source` can fail here:
     *
     * - The ConfigSet row is new in this transaction.
     * - [RepositoryPath], [Branch] and [RootPath] are stricter than the check constraints of the table.
     * - The caller holds the exclusive tree lock, and a credential delete takes the shared tree lock before it locks
     *   the row (design 8.1). A delete that commits first gives `CredentialNotFound` before this insert. A later delete
     *   sees this row and gives `CredentialInUse`.
     */
    fun insert(configSet: ConfigSetId, source: GitSourceDefinition) {
        jdbc
            .sql(
                """
                insert into git_source (config_set_id, credential_id, repository_path, branch, root_path)
                values (:configSetId, :credentialId, :repositoryPath, :branch, :rootPath)
                """.trimIndent(),
            ).param("configSetId", configSet.value)
            .param("credentialId", source.credential.value)
            .param("repositoryPath", source.repositoryPath.value)
            .param("branch", source.branch.value)
            .param("rootPath", source.rootPath.value)
            .update()
    }

    fun find(configSet: ConfigSetId): GitSourceDefinition? = jdbc
        .sql(
            """
                select credential_id, repository_path, branch, root_path
                from git_source where config_set_id = :configSetId
            """.trimIndent(),
        ).param("configSetId", configSet.value)
        .query { rs, _ -> rs.toDefinition() }
        .optional()
        .orElse(null)

    /**
     * Returns the Source definition if the ConfigSet published [revision], or null if it did not. Reads accept only
     * published revisions (design 5.5), so a commit that a fetch got but that sync did not record gives null.
     */
    fun findPublished(configSet: ConfigSetId, revision: RevisionId): GitSourceDefinition? = jdbc
        .sql(
            """
                select s.credential_id, s.repository_path, s.branch, s.root_path
                from git_source s join git_revision r on r.config_set_id = s.config_set_id
                where s.config_set_id = :configSetId and r.commit_id = :commitId
            """.trimIndent(),
        ).param("configSetId", configSet.value)
        .param("commitId", revision.value)
        .query { rs, _ -> rs.toDefinition() }
        .optional()
        .orElse(null)

    /** The published revisions, newest first, with a maximum of [limit]. */
    fun revisions(configSet: ConfigSetId, limit: Int): List<Revision> = jdbc
        .sql(
            """
                select commit_id, published_at from git_revision
                where config_set_id = :configSetId
                order by published_at desc
                limit :limit
            """.trimIndent(),
        ).param("configSetId", configSet.value)
        .param("limit", limit)
        .query { rs, _ ->
            Revision(
                RevisionId(rs.getString("commit_id")),
                rs.getObject("published_at", OffsetDateTime::class.java).toInstant(),
            )
        }.list()

    /** The newest published revision, or null if the ConfigSet has no published revision. */
    fun latest(configSet: ConfigSetId): Revision? = revisions(configSet, 1).firstOrNull()

    /**
     * Publishes [commit] as the newest revision (design 10.6). Sync calls this method in the transaction that records
     * its result. A commit that the branch returns to, for example after a force push, gets a new publish time. Thus
     * the newest row is always the tip that sync fetched last, and `latest` is that tip.
     */
    fun publish(configSet: ConfigSetId, commit: ObjectId) {
        jdbc
            .sql(
                """
                insert into git_revision (config_set_id, commit_id) values (:configSetId, :commitId)
                on conflict (config_set_id, commit_id) do update set published_at = now()
                """.trimIndent(),
            ).param("configSetId", configSet.value)
            .param("commitId", commit.name)
            .update()
    }

    private fun ResultSet.toDefinition() = GitSourceDefinition(
        credential = CredentialId(getObject("credential_id", UUID::class.java)),
        repositoryPath = RepositoryPath(getString("repository_path")),
        branch = Branch(getString("branch")),
        rootPath = RootPath(getString("root_path")),
    )
}
