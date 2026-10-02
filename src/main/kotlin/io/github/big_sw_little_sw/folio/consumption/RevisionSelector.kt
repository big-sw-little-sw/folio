package io.github.big_sw_little_sw.folio.consumption

/**
 * Which revision a consumer reads (design 6.4, 8.4): `latest`, the ConfigSet's last synced revision, or an exact
 * commit ID. Branches, tags and other Git ref syntax are not selectors.
 */
sealed interface RevisionSelector {
    data object Latest : RevisionSelector

    /** [commitId] is 40 lowercase hex characters. */
    data class Exact(
        val commitId: String,
    ) : RevisionSelector {
        init {
            if (!COMMIT_ID.matches(commitId)) throw InvalidRevisionException(commitId)
        }

        private companion object {
            val COMMIT_ID = Regex("[0-9a-f]{40}")
        }
    }

    companion object {
        const val LATEST = "latest"

        /** Throws [InvalidRevisionException] unless [text] is `latest` or a commit ID. */
        fun parse(text: String): RevisionSelector = if (text == LATEST) Latest else Exact(text)
    }
}
