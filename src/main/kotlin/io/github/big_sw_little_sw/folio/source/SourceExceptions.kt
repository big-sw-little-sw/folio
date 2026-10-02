package io.github.big_sw_little_sw.folio.source

/**
 * Expected failures of Git source operations (ADR 0006). Messages carry only values the caller supplied or
 * fixed text, never Git transport output (ADR 0027).
 */
sealed class SourceException(
    message: String,
) : RuntimeException(message)

class InvalidRepositoryPathException(
    val value: String,
) : SourceException("Invalid repository path '$value'")

class InvalidBranchException(
    val value: String,
) : SourceException("Invalid branch '$value'")

/** A path that is absolute, contains an empty, `.` or `..` segment, a backslash or NUL (ADR 0024). */
class InvalidSourcePathException(
    val value: String,
) : SourceException("Invalid path '$value'")

/** A commit ID that is not 40 lowercase hex characters. */
class InvalidCommitIdException(
    val value: String,
) : SourceException("Invalid commit ID '$value'")

/** The cache does not hold [revision]: it was never fetched, or the commit is not on the source's branch. */
class RevisionNotFoundException(
    val revision: RevisionRef,
) : SourceException("Revision '$revision' not found")

/** No regular file at [path] beneath the root path at the revision read. */
class SourceFileNotFoundException(
    val path: SourcePath,
) : SourceException("File '$path' not found")

/** The file at [path] is larger than the [maxBytes] a read may load. */
class SourceFileTooLargeException(
    val path: SourcePath,
    val maxBytes: Int,
) : SourceException("File '$path' is larger than $maxBytes bytes")

/** Another fetch of the ConfigSet held the fetch lock for longer than the caller would wait (ADR 0036). */
class SourceBusyException : SourceException("Another fetch of the ConfigSet is running")

/** Fetching from the Git service failed; [failure] is the stable code and its fixed, safe summary. */
class SourceAccessFailedException(
    val failure: SourceFailure,
) : SourceException(failure.summary)
