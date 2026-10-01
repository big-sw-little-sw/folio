package io.github.big_sw_little_sw.folio

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class FolioApplicationTests {
    @Test
    fun contextLoads() {
        // Passes if the application context starts against a real PostgreSQL.
    }
}
