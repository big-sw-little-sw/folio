package io.github.big_sw_little_sw.folio.content

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * This class declares the `content` module for Spring Modulith (design 8). Kotlin has no `package-info` file, so a
 * class carries the annotations. The `content` module uses only the `catalog`, `identity` and `web` modules. The `git`
 * module implements [ConfigSource], so `content` never depends on `git`. The modularity test rejects each other
 * dependency.
 */
@PackageInfo
@ApplicationModule(allowedDependencies = ["catalog", "identity", "web"])
class ModuleMetadata
