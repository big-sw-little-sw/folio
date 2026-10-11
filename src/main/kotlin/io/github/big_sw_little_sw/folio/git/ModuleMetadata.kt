package io.github.big_sw_little_sw.folio.git

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * This class declares the `git` module for Spring Modulith (design 8). Kotlin has no `package-info` file, so this
 * class carries the annotations. The `git` module is the Git adapter of the `content` module and serves its own admin
 * routes. No other module uses it. The modularity test rejects each dependency that this list does not name.
 */
@PackageInfo
@ApplicationModule(allowedDependencies = ["audit", "catalog", "content", "credential", "identity", "web"])
class ModuleMetadata
