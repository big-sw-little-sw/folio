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

/** The body that creates a ConfigSet and its Git source (design 10.2, 10.3). The Source definition never changes. */
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

/**
 * The Git source of a ConfigSet and its newest published revision. In slice 6, sync adds the other fields of its
 * state (design 10.6).
 */
data class GitSourceResponse(
    val configSetId: String,
    val path: String,
    val credentialId: String,
    val repositoryPath: String,
    val branch: String,
    val rootPath: String,
    /** Null until sync publishes the first revision. */
    val lastPublishedCommit: String?,
    val lastPublishedAt: Instant?,
)

/**
 * The result of a check (design 10.3): `{"ok": true, "commitId": …}` or `{"ok": false, "code": …, "summary": …}`. The
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
)

fun CheckResult.toResponse() = when (this) {
    is CheckResult.Passed -> CheckResponse(ok = true, commitId = commitId.name)
    is CheckResult.Failed -> CheckResponse(ok = false, code = code.name, summary = code.summary)
}
