package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component

/** A ConfigSet that the caller can READ. [public] is true if an anonymous caller can READ the ConfigSet too. */
data class ReadableConfigSet(val configSet: ConfigSet, val public: Boolean)

/**
 * ReadAccess does steps 1 and 2 of each read (design 8.2): it finds the ConfigSet, then it authorizes `READ`. The
 * steps run in one snapshot, so the ConfigSet, the decision and [ReadableConfigSet.public] agree.
 *
 * The transaction must end before [ContentService] calls the adapter. Spring starts a transaction only for a call
 * through a bean, so this class is a separate bean. Thus a slow read or a fetch on demand holds no database
 * connection.
 */
@Component
class ReadAccess(private val configSets: ConfigSets, private val access: Access) {
    /**
     * Throws ConfigSetNotFound (404) if the ConfigSet does not exist. Then it throws Forbidden (403) or Unauthenticated
     * (401) if the caller cannot READ the ConfigSet (design 7.4).
     */
    @SnapshotRead
    fun require(caller: Caller, id: ConfigSetId): ReadableConfigSet {
        val configSet = configSets.find(id) ?: throw CatalogException.ConfigSetNotFound(id)
        access.requireAllowed(caller, Action.READ, id)
        return ReadableConfigSet(configSet, access.isPublic(id))
    }
}
