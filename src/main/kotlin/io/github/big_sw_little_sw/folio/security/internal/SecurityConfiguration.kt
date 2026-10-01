package io.github.big_sw_little_sw.folio.security.internal

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
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/error", permitAll)
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
            runCatching { claimNames.toPrincipal(jwt.claims) }.fold(
                onSuccess = { OAuth2TokenValidatorResult.success() },
                onFailure = {
                    OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, it.message, null))
                },
            )
        }
}
