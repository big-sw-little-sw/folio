package io.github.big_sw_little_sw.folio.git.web

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.big_sw_little_sw.folio.credential.CREDENTIAL_ID
import io.github.big_sw_little_sw.folio.git.internal.Branch
import io.github.big_sw_little_sw.folio.git.internal.CheckResult
import io.github.big_sw_little_sw.folio.git.internal.GitSourceDefinition
import io.github.big_sw_little_sw.folio.git.internal.GitSourceView
import io.github.big_sw_little_sw.folio.git.internal.RepositoryPath
import io.github.big_sw_little_sw.folio.git.internal.RootPath
import java.time.Instant

/** The body that creates a ConfigSet and its Git source. The Source definition never changes. */
data class CreateGitConfigSetRequest(
    val namespaceId: String,
    val slug: String,
    val description: String? = null,
    /** The credential names the Git instance. The caller needs `MANAGE` on the Namespace of the credential. */
    val credentialId: String,
    /** The path of the repository on the Git instance, for example `org/repo.git`. */
    val repositoryPath: String,
    /** A branch name without `refs/heads/`, for example `main`. */
    val branch: String,
    /** The directory with the ConfigItems. An absent or empty root path is the root of the repository. */
    val rootPath: String? = null,
)

/** A check with [keyId] uses that pending key of the credential, not the active key. */
data class CheckRequest(val keyId: String? = null)

/** The Git source of a ConfigSet, its newest published revision and its sync state. */
data class GitSourceResponse(
    val configSetId: String,
    val path: String,
    val credentialId: String,
    val repositoryPath: String,
    val branch: String,
    val rootPath: String,
    /** Null before the first publish, as [lastPublishedAt] is. */
    val lastPublishedCommit: String?,
    val lastPublishedAt: Instant?,
    /**
     * The tip that the last successful fetch got. It differs from [lastPublishedCommit] if the publish step refused the
     * tip, for example because the root path is missing.
     */
    val lastSeenCommit: String?,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    /** The failure code of the last attempt, or null after a success or before the first attempt. */
    val failureCode: String?,
    /** The fixed, safe summary of [failureCode]. */
    val failureSummary: String?,
    val consecutiveFailures: Int,
    /**
     * The next due time: from this time, an instance can sync the ConfigSet. During a sync, the next due time is the
     * lease end, unless a manual sync moved it earlier.
     */
    val nextDueAt: Instant,
    /** True while an instance holds the lease of the ConfigSet, so a sync runs. */
    val syncing: Boolean,
)

/**
 * The result of a check: `{"ok": true, "commitId": …}` or `{"ok": false, "code": …, "summary": …}`. The
 * summary is the fixed, safe text of the code.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CheckResponse(
    val ok: Boolean,
    val commitId: String? = null,
    val code: String? = null,
    val summary: String? = null,
)

/** Validates the values in the order of the fields. The first value that is not valid gives status 400. */
fun sourceDefinitionOf(credentialId: String, repositoryPath: String, branch: String, rootPath: String?) =
    GitSourceDefinition(
        CREDENTIAL_ID.parse(credentialId),
        RepositoryPath(repositoryPath),
        Branch(branch),
        RootPath(rootPath.orEmpty()),
    )

fun GitSourceView.toResponse() = GitSourceResponse(
    configSetId = configSet.id.toApiId(),
    path = configSet.path.toString(),
    credentialId = source.credential.toApiId(),
    repositoryPath = source.repositoryPath.value,
    branch = source.branch.value,
    rootPath = source.rootPath.value,
    lastPublishedCommit = lastPublished?.id?.value,
    lastPublishedAt = lastPublished?.publishedAt,
    lastSeenCommit = sync.lastSeenCommit,
    lastAttemptAt = sync.lastAttemptAt,
    lastSuccessAt = sync.lastSuccessAt,
    failureCode = sync.failure?.name,
    failureSummary = sync.failure?.summary,
    consecutiveFailures = sync.consecutiveFailures,
    nextDueAt = sync.nextDueAt,
    syncing = sync.syncing,
)

fun CheckResult.toResponse() = when (this) {
    is CheckResult.Passed -> CheckResponse(ok = true, commitId = commitId.name)
    is CheckResult.Failed -> CheckResponse(ok = false, code = code.name, summary = code.summary)
}
