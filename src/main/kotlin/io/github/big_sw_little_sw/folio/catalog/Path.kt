package io.github.big_sw_little_sw.folio.catalog

/**
 * The name of a Namespace or ConfigSet in its parent, for example `service-a`. The constructor throws
 * [CatalogException.InvalidSlug] for text that is not a slug.
 */
@JvmInline
value class Slug(val value: String) {
    init {
        if (!isValid(value)) throw CatalogException.InvalidSlug()
    }

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 100
        private val PATTERN = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        fun isValid(text: String): Boolean = text.length <= MAX_LENGTH && PATTERN.matches(text)
    }
}

/**
 * The slugs from the top level down, joined by `/`. A rename or a move changes a path, but never an ID. Thus paths are
 * for people and for discovery only.
 */
@JvmInline
value class Path(val slugs: List<Slug>) {
    init {
        require(slugs.isNotEmpty()) { "A path has one or more segments" }
    }

    /** The path of a child named [slug]. */
    operator fun plus(slug: Slug): Path = Path(slugs + slug)

    override fun toString(): String = slugs.joinToString("/")

    companion object {
        /**
         * Throws [CatalogException.InvalidPath] for empty text, a slash at the start or the end, an empty segment or a
         * segment that is not a slug.
         */
        fun parse(text: String): Path {
            val segments = text.split('/')
            if (text.isEmpty() || !segments.all(Slug::isValid)) throw CatalogException.InvalidPath()
            return Path(segments.map(::Slug))
        }
    }
}
