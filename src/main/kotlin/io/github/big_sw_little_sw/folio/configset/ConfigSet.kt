package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.namespace.InvalidSlugException
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.Slug
import java.util.UUID

@JvmInline
value class ConfigSetId(
    val value: UUID,
)

/** Identity is the [id]; namespace and slug change on move and rename. Slice 5 adds the Git source. */
data class ConfigSet(
    val id: ConfigSetId,
    val namespaceId: NamespaceId,
    val slug: Slug,
)

/**
 * Where a ConfigSet is: the slugs of its namespaces from the root down, then its own slug (ADR 0013).
 * The text form joins them with `/`, with no leading or trailing slash: `engineering/ai/service-a`.
 */
data class ConfigSetPath(
    val namespacePath: List<Slug>,
    val slug: Slug,
) {
    init {
        require(namespacePath.isNotEmpty()) { "A ConfigSet always lives in a namespace" }
    }

    override fun toString() = (namespacePath + slug).joinToString("/")

    companion object {
        /** Throws [InvalidConfigSetPathException] unless [text] is two or more slugs separated by `/`. */
        fun parse(text: String): ConfigSetPath {
            val segments = text.split('/')
            if (segments.size < 2) throw InvalidConfigSetPathException(text)
            val slugs =
                try {
                    segments.map(::Slug)
                } catch (_: InvalidSlugException) {
                    throw InvalidConfigSetPathException(text)
                }
            return ConfigSetPath(slugs.dropLast(1), slugs.last())
        }
    }
}
