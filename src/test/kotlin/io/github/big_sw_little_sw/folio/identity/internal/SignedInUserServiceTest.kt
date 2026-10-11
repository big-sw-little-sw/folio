package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SignedInUserServiceTest {
    private val names = IdentityProperties.ClaimNames(subject = "oid")
    private val service = SignedInUserService(CallerClaims(names, TokenDirectory(names.groups)), names.subject)

    @Test
    fun `sign-in makes the caller from the ID token with the configured claim names`() {
        val user = service.loadUser(request(mapOf("sub" to "pairwise", "oid" to "alice", "groups" to listOf("team-a"))))

        assertIs<SignedInUser>(user)
        assertEquals(Caller.Authenticated("alice", setOf("team-a"), null), user.caller)
        // The layout shows the principal name, so it must be the subject of the caller.
        assertEquals("alice", user.name)
    }

    @Test
    fun `an ID token that gives no caller stops the sign-in`() {
        assertFailsWith<OAuth2AuthenticationException> { service.loadUser(request(mapOf("sub" to "pairwise"))) }
    }

    @Test
    fun `the session keeps the caller through Java serialization`() {
        val user = service.loadUser(request(mapOf("sub" to "s", "oid" to "alice", "groups" to listOf("a", "b"))))

        val copy = deserialize(serialize(user))

        assertIs<SignedInUser>(copy)
        assertEquals(Caller.Authenticated("alice", setOf("a", "b"), null), copy.caller)
    }

    private fun request(claims: Map<String, Any>): OidcUserRequest {
        val now = Instant.now()
        val registration =
            ClientRegistration
                .withRegistrationId("folio")
                .clientId("folio-ui")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://issuer.test/authorize")
                .tokenUri("https://issuer.test/token")
                .build()
        val accessToken = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now, now + HOUR)
        val idToken = OidcIdToken("id", now, now + HOUR, claims)
        return OidcUserRequest(registration, accessToken, idToken)
    }

    private fun serialize(value: Any): ByteArray = ByteArrayOutputStream().use { bytes ->
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        bytes.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): Any =
        ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

    private companion object {
        val HOUR: Duration = Duration.ofHours(1)
    }
}
