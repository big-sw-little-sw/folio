package io.github.big_sw_little_sw.folio.catalog

/**
 * A bundle of configuration files from one Source (design 2.1). Consumers keep its [id], not its [path], because a
 * rename or a move changes the path.
 */
data class ConfigSet(
    val id: ConfigSetId,
    val namespaceId: NamespaceId,
    val slug: Slug,
    val description: String?,
    val sourceType: SourceType,
    val path: Path,
)
