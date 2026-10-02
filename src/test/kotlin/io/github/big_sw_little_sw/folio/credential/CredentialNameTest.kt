package io.github.big_sw_little_sw.folio.credential

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CredentialNameTest {
    @ParameterizedTest
    @ValueSource(strings = ["a", "7", "payments-bot", "ci-2"])
    fun `accepts lowercase letters, digits and single inner hyphens`(value: String) {
        assertEquals(value, CredentialName(value).value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Bot", "payments_bot", "payments bot", "-a", "a-", "a--b", "a/b", "ä"])
    fun `rejects other characters, empty names and leading, trailing or repeated hyphens`(value: String) {
        val failure = assertFailsWith<InvalidCredentialNameException> { CredentialName(value) }
        assertEquals(value, failure.name)
    }

    @Test
    fun `accepts 100 characters and rejects 101`() {
        CredentialName("a".repeat(100))
        assertFailsWith<InvalidCredentialNameException> { CredentialName("a".repeat(101)) }
    }
}
