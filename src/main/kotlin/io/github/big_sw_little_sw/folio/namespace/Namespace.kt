package io.github.big_sw_little_sw.folio.namespace

import java.util.UUID

@JvmInline
value class NamespaceId(
    val value: UUID,
)

/**
 * A path segment: 1 to 100 characters of lowercase letters, digits and single hyphens,
 * starting and ending with a letter or digit (for example `service-a`).
 */
@JvmInline
value class Slug(
    val value: String,
) {
    init {
        if (value.length > MAX_LENGTH || !PATTERN.matches(value)) throw InvalidSlugException(value)
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 100
        val PATTERN = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}

/** A root namespace has no parent. Identity is the [id]; slug and parent change on rename and move. */
data class Namespace(
    val id: NamespaceId,
    val parentId: NamespaceId?,
    val slug: Slug,
)
