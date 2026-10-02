package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.configset.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.namespace.NamespaceTree
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** ConfigSet sources and paths for sync, which runs without a caller. Nothing here authorizes (ADR 0032). */
@Service
class ConfigSetSources(
    private val configSets: ConfigSetRepository,
    private val tree: NamespaceTree,
) {
    /** The source of [id], or null if the ConfigSet does not exist. */
    @Transactional(readOnly = true)
    fun find(id: ConfigSetId): SourceDefinition? = configSets.findById(id)?.source

    /** The path of [id], for sync's audit records (ADR 0038), or null if the ConfigSet does not exist. */
    @Transactional(readOnly = true)
    fun path(id: ConfigSetId): ConfigSetPath? =
        configSets.findById(id)?.let { ConfigSetPath(tree.slugPath(it.namespaceId), it.slug) }
}
