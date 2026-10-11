package io.github.big_sw_little_sw.folio.content

/**
 * The ID of a published revision (design 5.5). For consumers, the ID is an opaque string. For Git, it is the commit ID
 * as 40 lowercase hex characters.
 *
 * Each Source must give IDs of 1 to 64 lowercase letters and digits. Thus content can reject a malformed `revision`
 * value with status 400 before it finds the ConfigSet, and an ID needs no escape in a URL. An ID is never `latest`,
 * because `latest` selects the newest revision. The constructor throws [ContentException.InvalidRevision] for other
 * text.
 */
@JvmInline
value class RevisionId(val value: String) {
    init {
        if (!PATTERN.matches(value) || value == LATEST) throw ContentException.InvalidRevision()
    }

    override fun toString(): String = value

    companion object {
        /** The `revision` value that selects the newest published revision. */
        const val LATEST = "latest"

        private val PATTERN = Regex("[0-9a-z]{1,64}")
    }
}
