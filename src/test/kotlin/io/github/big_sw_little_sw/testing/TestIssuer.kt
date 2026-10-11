package io.github.big_sw_little_sw.testing

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistrar
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

/**
 * An OIDC issuer in the test process. It serves the discovery document and the signing keys, so Folio validates
 * tokens with its production decoder. Tests sign tokens with any claims, key, issuer or expiry time.
 */
object TestIssuer {
    /** Matches `audiences` in src/test/resources/config/application.yaml. */
    const val AUDIENCE = "folio"
    private const val KEY_SIZE = 2048
    private const val KEY_ID = "test-key"

    private val key: RSAKey = RSAKeyGenerator(KEY_SIZE).keyID(KEY_ID).generate()

    /** Another key with the same key ID, for tokens with a signature that is not valid. */
    val foreignKey: RSAKey = RSAKeyGenerator(KEY_SIZE).keyID(KEY_ID).generate()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/.well-known/openid-configuration") { respond(it, discovery()) }
            createContext("/jwks") { respond(it, JWKSet(key.toPublicJWK()).toString()) }
            start()
        }

    val uri: String = "http://localhost:${server.address.port}"

    fun token(
        claims: Map<String, Any> = mapOf("sub" to "alice"),
        /** Null gives a token without the `aud` claim. */
        audience: String? = AUDIENCE,
        issuer: String = uri,
        expiresAt: Instant = Instant.now() + Duration.ofMinutes(5),
        signingKey: RSAKey = key,
    ): String {
        val claimSet =
            JwtClaimsSet
                .builder()
                .issuer(issuer)
                .issuedAt(expiresAt - Duration.ofMinutes(10))
                .expiresAt(expiresAt)
                .claims { it.putAll(claims) }
                .apply { audience?.let { audience(listOf(it)) } }
                .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build()
        return NimbusJwtEncoder(ImmutableJWKSet(JWKSet(signingKey)))
            .encode(JwtEncoderParameters.from(header, claimSet))
            .tokenValue
    }

    /** The fields that Spring Security needs for the resource server and for the UI client registration. */
    private fun discovery() =
        """
        {
          "issuer": "$uri",
          "authorization_endpoint": "$uri/authorize",
          "token_endpoint": "$uri/token",
          "jwks_uri": "$uri/jwks",
          "response_types_supported": ["code"],
          "subject_types_supported": ["public"],
          "id_token_signing_alg_values_supported": ["RS256"],
          "token_endpoint_auth_methods_supported": ["client_secret_basic"],
          "code_challenge_methods_supported": ["S256"]
        }
        """.trimIndent()

    private fun respond(exchange: HttpExchange, json: String) {
        val body = json.toByteArray()
        exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json")
        exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private const val HTTP_OK = 200
}

/** Makes [TestIssuer] the issuer of the API and of the UI client. */
@TestConfiguration(proxyBeanMethods = false)
class TestIssuerConfiguration {
    @Bean
    fun testIssuerProperties() = DynamicPropertyRegistrar { registry ->
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIssuer::uri)
        registry.add("spring.security.oauth2.client.provider.folio.issuer-uri", TestIssuer::uri)
    }
}
