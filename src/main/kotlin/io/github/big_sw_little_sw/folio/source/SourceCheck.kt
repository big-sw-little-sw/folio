package io.github.big_sw_little_sw.folio.source

/**
 * Why Git access failed, as a stable code with a fixed summary that is safe to show and store: it never contains
 * transport output, keys or secrets (ADR 0027).
 */
enum class SourceFailure(
    val summary: String,
) {
    HOST_KEY_REJECTED("The server's host key is not among the trusted host keys of the Git instance"),
    AUTH_FAILED("The Git service rejected the credential's key"),
    REPOSITORY_NOT_FOUND("The repository does not exist or the credential cannot read it"),
    BRANCH_NOT_FOUND("The branch does not exist in the repository"),
    ROOT_PATH_NOT_FOUND("The root path is not a directory at the tip of the branch"),
    UNREACHABLE("The Git service could not be reached"),
    TRANSPORT_FAILURE("Git transport failed"),

    /** The fetch was aborted at `folio.git.fetch-deadline` (ADR 0033). */
    DEADLINE_EXCEEDED("The fetch did not finish within the fetch deadline"),

    // The credential cannot be used: sync records these, the onboarding check refuses them instead (ADR 0032).
    CREDENTIAL_DISABLED("The source's credential is disabled"),
    GIT_INSTANCE_NOT_CONFIGURED("The Git instance of the source's credential is not configured"),
    NO_ACTIVE_KEY("The source's credential does not exist or has no active key"),
}

/** The outcome of an onboarding check (ADR 0027). */
sealed interface SourceCheck {
    /** The credential reaches the repository, the branch exists and its tip [commitId] has the root path. */
    data class Passed(
        val commitId: String,
    ) : SourceCheck

    data class Failed(
        val failure: SourceFailure,
    ) : SourceCheck
}
