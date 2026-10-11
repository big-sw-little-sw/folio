package io.github.big_sw_little_sw.folio.catalog

/**
 * The Sources that this build contains (design 9). The catalog stores the type of each ConfigSet, and content
 * selects the adapter by it. The enum is in the catalog, because content depends on the catalog.
 */
enum class SourceType {
    GIT,
}
