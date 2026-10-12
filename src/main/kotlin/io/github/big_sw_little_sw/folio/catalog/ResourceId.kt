package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.web.ApiId
import java.util.UUID

/** The ID of a resource that rules can name: a Namespace or a ConfigSet. */
sealed interface ResourceId {
    val value: UUID

    /** The API form, for example `ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e`. */
    fun toApiId(): String = when (this) {
        is NamespaceId -> NAMESPACE_ID.format(this)
        is ConfigSetId -> CONFIG_SET_ID.format(this)
    }
}

@JvmInline
value class NamespaceId(override val value: UUID) : ResourceId

@JvmInline
value class ConfigSetId(override val value: UUID) : ResourceId

val NAMESPACE_ID = ApiId("ns", ::NamespaceId, NamespaceId::value)

val CONFIG_SET_ID = ApiId("cfg", ::ConfigSetId, ConfigSetId::value)
