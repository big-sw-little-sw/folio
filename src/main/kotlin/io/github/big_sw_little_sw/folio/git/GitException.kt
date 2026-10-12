package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException

/**
 * The expected failures of the Git routes. No other module calls the `git` module, so only its own routes throw these
 * errors. A failed Git check is not an error: the check gives a result with a failure code.
 */
sealed class GitException(kind: ErrorKind, code: String, detail: String) : FolioException(kind, code, detail) {
    class InvalidRepositoryPath :
        GitException(
            ErrorKind.INVALID,
            "INVALID_REPOSITORY_PATH",
            "A repository path has 500 characters or fewer: segments of letters, digits, '.', '_' and '-', joined by " +
                "'/'. No segment is '.' or '..'.",
        )

    class InvalidBranch :
        GitException(
            ErrorKind.INVALID,
            "INVALID_BRANCH",
            "A branch is a valid Git branch name of 250 characters or fewer, without 'refs/heads/'.",
        )

    class InvalidRootPath :
        GitException(
            ErrorKind.INVALID,
            "INVALID_ROOT_PATH",
            "A root path is empty, or segments joined by '/' with 1,000 characters or fewer. No segment is empty, " +
                "'.' or '..', and no segment contains a backslash or NUL.",
        )

    /** The ConfigSet exists, but it has no Git source. Only a ConfigSet of another Source type has none. */
    class GitSourceNotFound(id: ConfigSetId) :
        GitException(ErrorKind.NOT_FOUND, "GIT_SOURCE_NOT_FOUND", "ConfigSet ${id.toApiId()} has no Git source.")
}
