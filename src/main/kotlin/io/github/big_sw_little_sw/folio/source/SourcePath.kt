package io.github.big_sw_little_sw.folio.source

/**
 * A path inside a Git tree, relative and already normal: segments joined by `/`, none of them empty, `.` or `..`,
 * and no backslash or NUL. The empty path is the repository root. Reads resolve file paths beneath a ConfigSet's
 * root path, and a normal path cannot climb out of it (ADR 0024).
 */
@JvmInline
value class SourcePath private constructor(
    val value: String,
) {
    val isRoot: Boolean get() = value.isEmpty()

    /** [child] beneath this path. */
    fun resolve(child: SourcePath): SourcePath =
        when {
            isRoot -> child
            child.isRoot -> this
            else -> SourcePath("$value/${child.value}")
        }

    override fun toString() = value

    companion object {
        /** Matches the `config_set.root_path` column. */
        private const val MAX_LENGTH = 1000

        val ROOT = SourcePath("")

        /** Throws [InvalidSourcePathException] unless [text] is a normal relative path; see [SourcePath]. */
        fun parse(text: String): SourcePath = parseOrNull(text) ?: throw InvalidSourcePathException(text)

        fun parseOrNull(text: String): SourcePath? =
            when {
                text.isEmpty() -> ROOT
                text.length > MAX_LENGTH || text.any { it == '\\' || it == '\u0000' } -> null
                text.split('/').any { it.isEmpty() || it == "." || it == ".." } -> null
                else -> SourcePath(text)
            }
    }
}
