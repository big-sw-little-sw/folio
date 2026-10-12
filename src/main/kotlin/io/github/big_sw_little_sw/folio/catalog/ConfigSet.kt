package io.github.big_sw_little_sw.folio.catalog

/**
 * A bundle of configuration files from one Source. Consumers keep its [id], not its [path], because a rename or a move
 * changes the path.
 */
data class ConfigSet(
    val id: ConfigSetId,
    val namespaceId: NamespaceId,
    val slug: Slug,
    val description: String?,
    val sourceType: SourceType,
    val path: Path,
)

/** A ConfigSet that the caller can READ. [public] is true if an anonymous caller can READ the ConfigSet too. */
data class ReadableConfigSet(val configSet: ConfigSet, val public: Boolean)
