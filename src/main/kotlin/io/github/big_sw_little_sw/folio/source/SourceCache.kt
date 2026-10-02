package io.github.big_sw_little_sw.folio.source

import io.github.big_sw_little_sw.folio.source.internal.RepositoryCache
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Removal of cached repositories on this instance, for ConfigSets that no longer exist (ADR 0034). The cache is
 * disposable, so removing a repository costs at most a full fetch.
 */
@Service
class SourceCache(
    private val cache: RepositoryCache,
) {
    /** The IDs of the ConfigSets that have a cached repository on this instance. */
    fun configSetIds(): Set<UUID> = cache.ids()

    /** Deletes the cached repository of [configSetId], if there is one. */
    fun delete(configSetId: UUID) {
        cache.delete(configSetId)
    }
}
