package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.InvalidCredentialIdException
import io.github.big_sw_little_sw.folio.credential.InvalidKeyIdException
import io.github.big_sw_little_sw.folio.credential.KeyId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CredentialApiIdsTest {
    private val uuid = UUID.fromString("0192F3A4-5B6C-7D8E-9FA0-B1C2D3E4F5A6")

    @Test
    fun `formats cred_ and key_ IDs and parses them back`() {
        assertEquals("cred_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6", CredentialId(uuid).toApiId())
        assertEquals("key_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6", KeyId(uuid).toApiId())
        assertEquals(CredentialId(uuid), CredentialId(uuid).toApiId().toCredentialId())
        assertEquals(KeyId(uuid), KeyId(uuid).toApiId().toKeyId())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "cred_",
            "key_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6",
            "cred_0192F3A45B6C7D8E9FA0B1C2D3E4F5A6",
            "cred_0192f3a4-5b6c-7d8e-9fa0-b1c2d3e4f5a6",
            "cred_0192f3a45b6c7d8e9fa0b1c2d3e4f5a",
        ],
    )
    fun `rejects anything else as a credential ID`(value: String) {
        assertEquals(value, assertFailsWith<InvalidCredentialIdException> { value.toCredentialId() }.value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "key_", "cred_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6", "key_0192f3a45b6c7d8e9fa0b1c2d3e4f5g6"],
    )
    fun `rejects anything else as a key ID`(value: String) {
        assertEquals(value, assertFailsWith<InvalidKeyIdException> { value.toKeyId() }.value)
    }
}
