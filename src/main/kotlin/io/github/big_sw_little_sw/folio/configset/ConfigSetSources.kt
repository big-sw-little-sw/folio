package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.configset.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** ConfigSet sources for sync, which runs without a caller. Nothing here authorizes (ADR 0032). */
@Service
class ConfigSetSources(
    private val configSets: ConfigSetRepository,
) {
    /** The source of [id], or null if the ConfigSet does not exist. */
    @Transactional(readOnly = true)
    fun find(id: ConfigSetId): SourceDefinition? = configSets.findById(id)?.source
}
