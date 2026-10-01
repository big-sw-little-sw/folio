package io.github.big_sw_little_sw.folio.namespace.web

import io.github.big_sw_little_sw.folio.namespace.InvalidNamespaceIdException
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NamespaceApiIdsTest {
    @Test
    fun `formats ns_ and the UUID as 32 lowercase hex characters and parses it back`() {
        val id = NamespaceId(UUID.fromString("0192F3A4-5B6C-7D8E-9FA0-B1C2D3E4F5A6"))

        assertEquals("ns_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6", id.toApiId())
        assertEquals(id, id.toApiId().toNamespaceId())
    }

    @Test
    fun `round-trips UUIDs with the high bit set`() {
        val id = NamespaceId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))

        assertEquals(id, id.toApiId().toNamespaceId())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "ns_",
            "0192f3a45b6c7d8e9fa0b1c2d3e4f5a6",
            "cfg_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6",
            "ns_0192F3A45B6C7D8E9FA0B1C2D3E4F5A6",
            "ns_0192f3a4-5b6c-7d8e-9fa0-b1c2d3e4f5a6",
            "ns_0192f3a45b6c7d8e9fa0b1c2d3e4f5a",
            "ns_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6f",
            "ns_0192f3a45b6c7d8e9fa0b1c2d3e4f5g6",
        ],
    )
    fun `rejects anything else`(value: String) {
        val failure = assertFailsWith<InvalidNamespaceIdException> { value.toNamespaceId() }
        assertEquals(value, failure.value)
    }
}
