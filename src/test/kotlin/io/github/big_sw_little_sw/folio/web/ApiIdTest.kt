package io.github.big_sw_little_sw.folio.web

import java.util.UUID
import kotlin.test.*

class ApiIdTest {
    @JvmInline
    value class NamespaceId(val value: UUID)

    private val apiId = ApiId("ns", ::NamespaceId, NamespaceId::value)
    private val uuid = UUID.fromString("0192f0c1-e8a4-7c3b-9d2e-5f6a7b8c9d0e")

    @Test
    fun `formats the prefix and the UUID as 32 lowercase hex characters`() {
        assertEquals("ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e", apiId.format(NamespaceId(uuid)))
    }

    @Test
    fun `parses its own format`() {
        assertEquals(NamespaceId(uuid), apiId.parse("ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e"))
        assertEquals(NamespaceId(UUID(-1, -1)), apiId.parse("ns_" + "f".repeat(32)))
    }

    @Test
    fun `rejects other forms as INVALID`() {
        listOf(
            "ns_0192F0C1E8A47C3B9D2E5F6A7B8C9D0E",
            "ns_0192f0c1-e8a4-7c3b-9d2e-5f6a7b8c9d0e",
            "cfg_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e",
            "ns0192f0c1e8a47c3b9d2e5f6a7b8c9d0e",
            "ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0",
            "ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e0",
            "ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0g",
            "0192f0c1e8a47c3b9d2e5f6a7b8c9d0e",
            "",
        ).forEach { value ->
            val exception = assertFailsWith<InvalidApiId>(value) { apiId.parse(value) }
            assertEquals(ErrorKind.INVALID, exception.kind)
            assertEquals("INVALID_ID", exception.code)
        }
    }

    @Test
    fun `a type is lowercase letters`() {
        listOf("", "Ns", "ns_", "n1").forEach { type ->
            assertFailsWith<IllegalArgumentException>(type) { ApiId(type, ::NamespaceId, NamespaceId::value) }
        }
    }
}
