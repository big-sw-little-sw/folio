package io.github.big_sw_little_sw.folio.policy

import java.util.UUID

/**
 * A resource that rules attach to (design 8.3). Policy does not depend on the modules that own these
 * resources, so it holds their UUIDs; the owning modules convert their ID types.
 */
sealed interface ResourceRef {
    val id: UUID

    data class NamespaceRef(
        override val id: UUID,
    ) : ResourceRef

    data class ConfigSetRef(
        override val id: UUID,
    ) : ResourceRef
}
