package io.github.big_sw_little_sw.folio.configset

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigSetApiIdsTest {
    @Test
    fun `formats cfg_ and the UUID as 32 lowercase hex characters and parses it back`() {
        val id = ConfigSetId(UUID.fromString("0192F3A4-5B6C-7D8E-9FA0-B1C2D3E4F5A6"))

        assertEquals("cfg_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6", id.toApiId())
        assertEquals(id, id.toApiId().toConfigSetId())
    }

    @Test
    fun `round-trips UUIDs with the high bit set`() {
        val id = ConfigSetId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))

        assertEquals(id, id.toApiId().toConfigSetId())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "cfg_",
            "0192f3a45b6c7d8e9fa0b1c2d3e4f5a6",
            "ns_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6",
            "cfg_0192F3A45B6C7D8E9FA0B1C2D3E4F5A6",
            "cfg_0192f3a4-5b6c-7d8e-9fa0-b1c2d3e4f5a6",
            "cfg_0192f3a45b6c7d8e9fa0b1c2d3e4f5a",
            "cfg_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6f",
            "cfg_0192f3a45b6c7d8e9fa0b1c2d3e4f5g6",
        ],
    )
    fun `rejects anything else`(value: String) {
        val failure = assertFailsWith<InvalidConfigSetIdException> { value.toConfigSetId() }
        assertEquals(value, failure.value)
    }
}
