package io.github.big_sw_little_sw.folio.content

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException
import java.time.Duration

/**
 * ContentException declares the expected failures of a read of a ConfigSet (design 9). Adapters of [ConfigSource]
 * throw these errors too. A detail repeats only
 * values that Folio validated, such as a revision ID. A detail never repeats an item path, because a path can contain
 * any text except a backslash or NUL.
 */
sealed class ContentException(kind: ErrorKind, code: String, detail: String, retryAfter: Duration? = null) :
    FolioException(kind, code, detail, retryAfter) {
    /** A read of `latest` found no revision, because the ConfigSet has no published revision (design 5.5). */
    class NotPublishedYet(id: ConfigSetId) :
        ContentException(ErrorKind.NOT_FOUND, "NOT_PUBLISHED_YET", "ConfigSet ${id.toApiId()} is not published yet.")

    /** The ConfigSet did not publish this revision. Consumers can read only published revisions (design 5.5). */
    class RevisionNotFound(revision: RevisionId) :
        ContentException(
            ErrorKind.NOT_FOUND,
            "REVISION_NOT_FOUND",
            "The ConfigSet did not publish revision $revision.",
        )

    /** The ConfigSet published the revision, but this instance cannot read it now (design 10.8). */
    class RevisionUnavailable(revision: RevisionId) :
        ContentException(
            ErrorKind.NOT_FOUND,
            "REVISION_UNAVAILABLE",
            "The ConfigSet published revision $revision, but Folio cannot read it now.",
        )

    class ItemNotFound :
        ContentException(ErrorKind.NOT_FOUND, "ITEM_NOT_FOUND", "The revision has no ConfigItem at this path.")

    /** The ConfigItem is larger than `folio.content.max-file-size`. */
    class ItemTooLarge(maxBytes: Long) :
        ContentException(
            ErrorKind.TOO_LARGE,
            "ITEM_TOO_LARGE",
            "The ConfigItem is larger than $maxBytes bytes, the limit of this deployment.",
        )

    /** The Source cannot do the read now. The client can try again after [retryAfter]. */
    class SourceBusy(retryAfter: Duration) :
        ContentException(
            ErrorKind.UNAVAILABLE,
            "SOURCE_BUSY",
            "The Source of the ConfigSet is busy. Try again later.",
            retryAfter,
        )

    /**
     * The Source failed (502). [code] is the code of the adapter, for example `UNREACHABLE`. [detail] is the fixed,
     * safe summary that the adapter gives for the code.
     */
    class SourceFailed(code: String, detail: String) : ContentException(ErrorKind.UPSTREAM_FAILED, code, detail)

    /**
     * The Source did not answer in time (504). [code] is the code of the adapter, for example `DEADLINE_EXCEEDED`.
     * [detail] is the fixed, safe summary that the adapter gives for the code.
     */
    class SourceTimedOut(code: String, detail: String) : ContentException(ErrorKind.UPSTREAM_TIMEOUT, code, detail)

    class InvalidRevision :
        ContentException(
            ErrorKind.INVALID,
            "INVALID_REVISION",
            "A revision is 'latest' or a revision ID of 1 to 64 lowercase letters and digits.",
        )

    class InvalidItemPath :
        ContentException(
            ErrorKind.INVALID,
            "INVALID_ITEM_PATH",
            "An item path is segments joined by '/'. No segment is empty, '.' or '..', and no segment contains a " +
                "backslash or NUL.",
        )
}
