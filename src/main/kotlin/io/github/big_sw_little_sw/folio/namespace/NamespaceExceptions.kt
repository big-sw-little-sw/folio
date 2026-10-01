package io.github.big_sw_little_sw.folio.namespace

/** Expected failures of namespace operations (ADR 0006). */
sealed class NamespaceException(
    message: String,
) : RuntimeException(message)

class NamespaceNotFoundException(
    val id: NamespaceId,
) : NamespaceException("Namespace ${id.value} not found")

class InvalidSlugException(
    val slug: String,
) : NamespaceException("Invalid slug '$slug'")

class DuplicateSlugException(
    val parentId: NamespaceId?,
    val slug: Slug,
) : NamespaceException("A sibling namespace with slug '$slug' already exists")

class NamespaceMoveIntoOwnSubtreeException(
    val id: NamespaceId,
    val newParentId: NamespaceId,
) : NamespaceException("Namespace ${id.value} cannot move into its own subtree")

class NamespaceNotEmptyException(
    val id: NamespaceId,
) : NamespaceException("Namespace ${id.value} is not empty")
