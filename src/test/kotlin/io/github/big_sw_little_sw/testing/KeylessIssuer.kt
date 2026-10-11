package io.github.big_sw_little_sw.testing

import com.sun.net.httpserver.HttpServer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistrar
import java.net.InetSocketAddress

/**
 * An issuer in an outage: it serves its discovery document, but its key set URL fails with status 503. Folio then
 * cannot validate any token of this issuer.
 */
object KeylessIssuer {
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/.well-known/openid-configuration") { exchange ->
                val body = discovery().toByteArray()
                exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/jwks") { exchange -> exchange.sendResponseHeaders(HTTP_UNAVAILABLE, -1) }
            start()
        }

    val uri: String = "http://localhost:${server.address.port}"

    private fun discovery() =
        """
        {
          "issuer": "$uri",
          "authorization_endpoint": "$uri/authorize",
          "token_endpoint": "$uri/token",
          "jwks_uri": "$uri/jwks",
          "response_types_supported": ["code"],
          "subject_types_supported": ["public"],
          "id_token_signing_alg_values_supported": ["RS256"]
        }
        """.trimIndent()

    private const val HTTP_OK = 200
    private const val HTTP_UNAVAILABLE = 503
}

/** Makes [KeylessIssuer] the issuer of the API and of the UI client. */
@TestConfiguration(proxyBeanMethods = false)
class KeylessIssuerConfiguration {
    @Bean
    fun keylessIssuerProperties() = DynamicPropertyRegistrar { registry ->
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeylessIssuer::uri)
        registry.add("spring.security.oauth2.client.provider.folio.issuer-uri", KeylessIssuer::uri)
    }
}
