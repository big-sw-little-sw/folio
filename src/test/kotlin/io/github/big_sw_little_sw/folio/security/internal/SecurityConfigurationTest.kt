package io.github.big_sw_little_sw.folio.security.internal

import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.jwt.Jwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecurityConfigurationTest {
    private val validator = SecurityConfiguration().principalClaimsValidator(ClaimNames())

    @Test
    fun `accepts a token whose claims map to a principal`() {
        assertFalse(validator.validate(jwt("sub" to "alice")).hasErrors())
    }

    @Test
    fun `rejects a token without a subject as an invalid token`() {
        val result = validator.validate(jwt("groups" to listOf("editors")))

        assertEquals(listOf(OAuth2ErrorCodes.INVALID_TOKEN), result.errors.map { it.errorCode })
    }

    private fun jwt(vararg claims: Pair<String, Any>): Jwt =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .claims { it.putAll(claims) }
            .build()
}
