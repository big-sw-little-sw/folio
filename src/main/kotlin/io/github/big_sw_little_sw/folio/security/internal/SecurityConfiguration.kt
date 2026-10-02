package io.github.big_sw_little_sw.folio.security.internal

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.web.SecurityFilterChain

/**
 * Bearer tokens only: stateless, no sessions and no CSRF, because v1 has no browser clients.
 * URL rules are a coarse first gate; application services authorize every operation through policy.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration(
    resourceServer: OAuth2ResourceServerProperties,
) {
    init {
        // Boot validates `aud` only when audiences are set. Without it, any token the issuer signed for
        // another client or API would be accepted (ADR 0002).
        require(resourceServer.jwt.audiences.any { it.isNotBlank() }) {
            "spring.security.oauth2.resourceserver.jwt.audiences must name the audience of Folio's tokens"
        }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/error", permitAll)
                // Consumption routes take anonymous callers too, so that `public` rules apply; their services
                // authorize every request (ADR 0035). A token that is sent must still be valid.
                authorize("/api/v1/configsets/**", permitAll)
                authorize("/api/v1/configsets:resolve", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer { jwt { } }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
        }
        return http.build()
    }

    /**
     * Rejects tokens whose claims do not map to a principal, so services never see them.
     * Boot adds every `OAuth2TokenValidator<Jwt>` bean to the issuer's JWT decoder.
     */
    @Bean
    fun principalClaimsValidator(claimNames: ClaimNames): OAuth2TokenValidator<Jwt> =
        OAuth2TokenValidator { jwt ->
            try {
                claimNames.toPrincipal(jwt.claims)
                OAuth2TokenValidatorResult.success()
            } catch (exception: IllegalArgumentException) {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, exception.message, null),
                )
            }
        }
}
