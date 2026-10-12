package io.github.big_sw_little_sw.folio.content

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declares the `content` module for Spring Modulith. Kotlin has no `package-info` file, so a class carries the
 * annotations. The `git` module implements [ConfigSource], so `content` never depends on `git`. The modularity test
 * fails for a dependency that the annotation does not allow.
 */
@PackageInfo
@ApplicationModule(allowedDependencies = ["catalog", "identity", "web"])
class ModuleMetadata
