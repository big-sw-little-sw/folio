package io.github.big_sw_little_sw.folio

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * A mock OIDC provider for local runs. It is the issuer of the API and of the UI client. Its sign-in page accepts any
 * user name and optional claims as JSON, for example `{"groups": ["team-a"]}`.
 */
@TestConfiguration(proxyBeanMethods = false)
class MockOidcProviderConfiguration {
    @Bean
    fun mockOidcProvider(): MockOidcProvider = MockOidcProvider()

    @Bean
    fun mockOidcProviderProperties(provider: MockOidcProvider) = DynamicPropertyRegistrar { registry ->
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", provider::issuer)
        registry.add("spring.security.oauth2.client.provider.folio.issuer-uri", provider::issuer)
    }

    /** The mapped port changes at each run, so a developer reads the issuer for API tokens from the log. */
    @Bean
    fun mockOidcProviderLog(provider: MockOidcProvider) = ApplicationRunner {
        LoggerFactory
            .getLogger(
                MockOidcProvider::class.java,
            ).info("Issuer: {}", provider.issuer())
    }
}

class MockOidcProvider : GenericContainer<MockOidcProvider>(IMAGE) {
    init {
        withExposedPorts(PORT)
        withEnv("JSON_CONFIG", """{"interactiveLogin": true}""")
        waitingFor(Wait.forHttp("/$ISSUER_ID/.well-known/openid-configuration"))
    }

    /** The issuer that tokens name. The provider takes the host of its URL from each request. */
    fun issuer(): String = "http://$host:$firstMappedPort/$ISSUER_ID"

    private companion object {
        const val IMAGE = "ghcr.io/navikt/mock-oauth2-server:6.0.5"
        const val PORT = 8080
        const val ISSUER_ID = "folio"
    }
}
