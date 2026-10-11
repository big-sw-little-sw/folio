package io.github.big_sw_little_sw.folio

import org.springframework.modulith.core.ApplicationModules
import kotlin.test.Test

/** The module boundaries of design section 8. Later slices add their own rules here. */
class ModularityTests {
    @Test
    fun `application modules respect their boundaries`() {
        ApplicationModules.of(FolioApplication::class.java).verify()
    }
}
