package io.github.big_sw_little_sw.folio.catalog

/** A folder in the tree, with its current path (design 2.1). [parentId] is null for a top-level Namespace. */
data class Namespace(
    val id: NamespaceId,
    val parentId: NamespaceId?,
    val slug: Slug,
    val description: String?,
    val path: Path,
)
