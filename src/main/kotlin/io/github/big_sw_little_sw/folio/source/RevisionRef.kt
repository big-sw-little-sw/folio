package io.github.big_sw_little_sw.folio.source

/** Which commit a read uses. */
sealed interface RevisionRef {
    /** The tip of the source's branch as last fetched into the cache. */
    data object Latest : RevisionRef {
        override fun toString() = "latest"
    }

    /** An exact commit; [id] is 40 lowercase hex characters. */
    data class Commit(
        val id: String,
    ) : RevisionRef {
        init {
            if (!COMMIT_ID.matches(id)) throw InvalidCommitIdException(id)
        }

        override fun toString() = id

        private companion object {
            val COMMIT_ID = Regex("[0-9a-f]{40}")
        }
    }
}
