package io.github.big_sw_little_sw.folio

import org.springframework.modulith.core.ApplicationModules
import kotlin.test.*

class ModularityTests {
    @Test
    fun `application modules respect their boundaries`() {
        ApplicationModules.of(FolioApplication::class.java).verify()
    }
}
