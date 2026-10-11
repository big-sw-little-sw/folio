package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.Authorizer
import io.github.big_sw_little_sw.folio.catalog.internal.CatalogAudit
import io.github.big_sw_little_sw.folio.catalog.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.catalog.internal.validDescription
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** ConfigSet creation for Source adapters (design 8.1). The HTTP routes of the catalog do the other operations. */
@Service
class ConfigSets(
    private val tree: TreeRepository,
    private val configSets: ConfigSetRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    /**
     * Creates the ConfigSet. It needs `MANAGE` on [namespace]. The adapter writes its Source definition in the same
     * transaction, and authorizes the use of its credential itself (design 7.3).
     */
    @Transactional
    fun create(
        caller: Caller,
        namespace: NamespaceId,
        slug: Slug,
        description: String?,
        sourceType: SourceType,
    ): ConfigSet {
        val text = validDescription(description)
        tree.lock()
        val ancestry = tree.ancestry(namespace) ?: throw CatalogException.NamespaceNotFound(namespace)
        authorizer.require(caller, Action.MANAGE, ancestry)
        val id = configSets.insert(namespace, slug, text, sourceType)
        val configSet = ConfigSet(id, namespace, slug, text, sourceType, ancestry.path + slug)
        audit.created(
            caller,
            id,
            configSet.path,
            mapOf("namespaceId" to namespace.toApiId(), "sourceType" to sourceType.name, "description" to text),
        )
        return configSet
    }

    /** Finds a ConfigSet without authorization. One snapshot keeps the row and its path consistent. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun find(id: ConfigSetId): ConfigSet? {
        val row = configSets.find(id) ?: return null
        val ancestry = tree.ancestry(id) ?: return null
        return row.toConfigSet(ancestry.path)
    }
}
