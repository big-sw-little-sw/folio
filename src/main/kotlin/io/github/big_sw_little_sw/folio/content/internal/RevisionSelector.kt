package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.RevisionId

/** The `revision` value of a read: `latest`, the newest published revision, or an exact revision ID. */
sealed interface RevisionSelector {
    data object Latest : RevisionSelector

    data class Exact(val id: RevisionId) : RevisionSelector

    companion object {
        /** Throws [ContentException.InvalidRevision] for a malformed ID. */
        fun parse(text: String): RevisionSelector = if (text == RevisionId.LATEST) Latest else Exact(RevisionId(text))
    }
}
