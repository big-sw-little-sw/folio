package io.github.big_sw_little_sw.folio.identity.internal

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.web.servlet.HandlerExceptionResolver
import kotlin.test.*

class SecurityConfigurationTest {
    private val errors = HandlerExceptionResolver { _, _, _, _ -> null }

    @Test
    fun `Folio does not start without a token audience`() {
        assertFailsWith<IllegalArgumentException> { configuration(resourceServer(ISSUER)) }
        assertFailsWith<IllegalArgumentException> { configuration(resourceServer(ISSUER, " ")) }
    }

    @Test
    fun `Folio does not start without an issuer`() {
        assertFailsWith<IllegalArgumentException> { configuration(resourceServer(null, "folio")) }
    }

    @Test
    fun `Folio does not start without the client registration of the UI`() {
        assertFailsWith<IllegalArgumentException> {
            configuration(resourceServer(ISSUER, "folio"), registration(registrationId = "other"))
        }
    }

    @Test
    fun `Folio does not start if the UI signs people in through another issuer`() {
        listOf("https://another-issuer.test", "$ISSUER/", null).forEach { issuer ->
            assertFailsWith<IllegalArgumentException>(issuer.toString()) {
                configuration(resourceServer(ISSUER, "folio"), registration(issuer = issuer))
            }
        }
    }

    @Test
    fun `Folio does not start if the UI client does not ask for an ID token`() {
        assertFailsWith<IllegalArgumentException> {
            configuration(resourceServer(ISSUER, "folio"), registration(scopes = setOf("profile")))
        }
    }

    @Test
    fun `Folio starts with an issuer, an audience and an OpenID client of the same issuer`() {
        configuration(resourceServer(ISSUER, "folio"))
    }

    @Test
    fun `with the google directory, Folio does not start if the UI client does not ask for the email claim`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                configuration(resourceServer(ISSUER, "folio"), registration(scopes = setOf("openid")), google)
            }
        assertEquals(
            "spring.security.oauth2.client.registration.folio.scope must contain email when " +
                "folio.security.directory is google",
            failure.message,
        )
    }

    @Test
    fun `with the google directory, Folio starts with the scopes openid and email`() {
        configuration(resourceServer(ISSUER, "folio"), registration(scopes = setOf("openid", "email")), google)
    }

    private fun configuration(
        resourceServer: OAuth2ResourceServerProperties,
        registration: ClientRegistration = registration(),
        identity: IdentityProperties = IdentityProperties(),
    ) = SecurityConfiguration(resourceServer, identity, InMemoryClientRegistrationRepository(registration), errors)

    private fun resourceServer(issuer: String?, vararg audiences: String) = OAuth2ResourceServerProperties().apply {
        jwt.issuerUri = issuer
        jwt.audiences = audiences.toList()
    }

    private fun registration(
        registrationId: String = SecurityConfiguration.REGISTRATION_ID,
        issuer: String? = ISSUER,
        scopes: Set<String> = setOf("openid", "profile"),
    ): ClientRegistration = ClientRegistration
        .withRegistrationId(registrationId)
        .clientId("folio-ui")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .authorizationUri("$ISSUER/authorize")
        .tokenUri("$ISSUER/token")
        .issuerUri(issuer)
        .scope(scopes)
        .build()

    private companion object {
        const val ISSUER = "https://issuer.test"
        val google =
            IdentityProperties(
                directory = IdentityProperties.DirectoryType.GOOGLE,
                google = IdentityProperties.Google(hostedDomain = "example.com"),
            )
    }
}
