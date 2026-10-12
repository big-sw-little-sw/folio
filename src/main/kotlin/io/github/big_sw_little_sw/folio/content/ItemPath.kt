package io.github.big_sw_little_sw.folio.content

/**
 * The path of a ConfigItem relative to the root of its ConfigSet, for example `kafka/client.properties`. The path is in
 * normal form: segments joined by `/`, no segment is empty, `.` or `..`, and no segment contains a backslash or NUL.
 * The constructor throws [ContentException.InvalidItemPath] for other text and never rewrites it. Thus an adapter can
 * join the segments below the root of the ConfigSet, and a path cannot go outside the root.
 */
@JvmInline
value class ItemPath(val value: String) {
    init {
        if (!isValid(value)) throw ContentException.InvalidItemPath()
    }

    val segments: List<String> get() = value.split('/')

    /** The name is the last segment, for example `client.properties`. */
    val name: String get() = value.substringAfterLast('/')

    override fun toString(): String = value

    companion object {
        fun isValid(text: String): Boolean = text.none { it == '\\' || it == '\u0000' } &&
            text.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
