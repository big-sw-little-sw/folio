package io.github.big_sw_little_sw.folio.git

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declares the `git` module for Spring Modulith. Kotlin has no `package-info` file, so a class carries the annotations.
 * The `git` module is the Git adapter of the `content` module and serves its own admin routes. No other module uses
 * it. The modularity test fails for a dependency that the annotation does not allow.
 */
@PackageInfo
@ApplicationModule(allowedDependencies = ["audit", "catalog", "content", "credential", "identity", "web"])
class ModuleMetadata
