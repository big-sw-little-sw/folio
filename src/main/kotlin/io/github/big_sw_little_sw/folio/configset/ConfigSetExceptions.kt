package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.Slug

/** Expected failures of ConfigSet operations (ADR 0006). */
sealed class ConfigSetException(
    message: String,
) : RuntimeException(message)

class ConfigSetNotFoundException(
    val id: ConfigSetId,
) : ConfigSetException("ConfigSet ${id.value} not found")

class ConfigSetPathNotFoundException(
    val path: ConfigSetPath,
) : ConfigSetException("No ConfigSet at '$path'")

/** An API ID that is not `cfg_` followed by 32 lowercase hex characters (ADR 0007). */
class InvalidConfigSetIdException(
    val value: String,
) : ConfigSetException("Invalid ConfigSet ID '$value'")

/** A path that is not two or more slugs separated by `/` (ADR 0013). */
class InvalidConfigSetPathException(
    val path: String,
) : ConfigSetException("Invalid ConfigSet path '$path'")

class DuplicateConfigSetSlugException(
    val namespaceId: NamespaceId,
    val slug: Slug,
) : ConfigSetException("A ConfigSet with slug '$slug' already exists in the namespace")
