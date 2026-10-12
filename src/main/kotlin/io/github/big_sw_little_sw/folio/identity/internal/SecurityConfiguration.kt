package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.IdentityException
import io.github.big_sw_little_sw.folio.web.CONTENT_SECURITY_POLICY
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.config.ObjectPostProcessor
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * Two filter chains. The services authorize each operation, so both chains let anonymous callers through.
 *
 * - `/api/` accepts only bearer tokens. It is stateless, ignores the session cookie and has no CSRF protection.
 * - All other routes are the UI. People sign in with the authorization code flow and PKCE. The session is in
 *   PostgreSQL, and forms need a CSRF token.
 *
 * Both chains give their failures to Spring MVC's exception resolvers, so the web module writes the error body.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration(
    resourceServer: OAuth2ResourceServerProperties,
    private val clients: ClientRegistrationRepository,
    @Qualifier("handlerExceptionResolver") private val errors: HandlerExceptionResolver,
) {
    init {
        val issuer = resourceServer.jwt.issuerUri
        require(!issuer.isNullOrBlank()) {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri must name the one trusted issuer"
        }
        // Spring Boot examines `aud` only when audiences are set. Without them, Folio would accept a token that the
        // issuer signed for a different API.
        require(resourceServer.jwt.audiences.any { it.isNotBlank() }) {
            "spring.security.oauth2.resourceserver.jwt.audiences must name the audience of Folio's tokens"
        }
        val registration =
            requireNotNull(clients.findByRegistrationId(REGISTRATION_ID)) {
                "spring.security.oauth2.client.registration.$REGISTRATION_ID must configure the web UI client"
            }
        // The UI signs people in through the one trusted issuer. Spring examines `iss` of an ID token only against
        // this value, and compares issuers as exact strings, so this check does too.
        require(registration.providerDetails.issuerUri == issuer) {
            "spring.security.oauth2.client.provider.$REGISTRATION_ID.issuer-uri must equal " +
                "spring.security.oauth2.resourceserver.jwt.issuer-uri"
        }
        // Without openid, Spring signs people in without an ID token, so sign-in makes no caller.
        require(OidcScopes.OPENID in registration.scopes) {
            "spring.security.oauth2.client.registration.$REGISTRATION_ID.scope must contain openid"
        }
    }

    @Bean
    fun callerFactory(properties: IdentityProperties): CallerFactory = CallerFactory(
        properties.claims,
        when (properties.directory) {
            IdentityProperties.DirectoryType.TOKEN -> TokenDirectory(properties.claims.groups)
        },
    )

    @Bean
    @Order(1)
    fun apiFilterChain(http: HttpSecurity, callerFactory: CallerFactory, decoder: JwtDecoder): SecurityFilterChain {
        // Spring calls this entry point only for a bearer token that it did not accept. All API routes permit all
        // callers, so a request without a token stays anonymous and never gets here.
        val bearerFailure =
            AuthenticationEntryPoint { request, response, exception -> failToken(request, response, exception) }
        http {
            securityMatcher(API)
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            oauth2ResourceServer {
                jwt {
                    jwtDecoder = OutageReportingJwtDecoder(decoder)
                    jwtAuthenticationConverter =
                        Converter<Jwt, AbstractAuthenticationToken> { jwt ->
                            BearerAuthentication(callerFactory.toCaller(jwt.claims))
                        }
                }
                authenticationEntryPoint = bearerFailure
            }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
        }
        // By default, the filter rethrows an issuer outage, and the container then sends a 500. The Kotlin DSL has no
        // setting for this, so a post-processor changes the failure handler of the filter.
        http.oauth2ResourceServer {
            it.withObjectPostProcessor(
                object : ObjectPostProcessor<BearerTokenAuthenticationFilter> {
                    override fun <O : BearerTokenAuthenticationFilter> postProcess(filter: O): O {
                        val failures = AuthenticationEntryPointFailureHandler(bearerFailure)
                        failures.setRethrowAuthenticationServiceException(false)
                        filter.setAuthenticationFailureHandler(failures)
                        return filter
                    }
                },
            )
        }
        return http.build()
    }

    @Bean
    @Order(2)
    fun uiFilterChain(http: HttpSecurity, callerFactory: CallerFactory): SecurityFilterChain {
        http {
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            oauth2Login {
                loginPage = "/sign-in"
                authorizationEndpoint { authorizationRequestResolver = withPkce() }
                userInfoEndpoint { oidcUserService = SignedInUserService(callerFactory) }
                authorizedClientRepository = NoAuthorizedClients
            }
            logout {
                logoutUrl = "/sign-out"
                logoutSuccessUrl = "/"
            }
            exceptionHandling { accessDeniedHandler = csrfRejected() }
            headers {
                contentSecurityPolicy { policyDirectives = CONTENT_SECURITY_POLICY }
            }
        }
        return http.build()
    }

    /** Folio is a confidential client, and Spring Security uses PKCE by default only for public clients. */
    private fun withPkce() = DefaultOAuth2AuthorizationRequestResolver(
        clients,
        OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI,
    ).apply {
        setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
    }

    /** All UI routes permit all callers, so a CSRF failure is the expected cause of a denial. */
    private fun csrfRejected() = AccessDeniedHandler { request, response, exception ->
        if (exception is CsrfException) {
            fail(request, response, IdentityException.CsrfRejected())
        } else {
            AccessDeniedHandlerImpl().handle(request, response, exception)
        }
    }

    /**
     * Spring gives [AuthenticationServiceException] when the decoder cannot get the discovery document or the signing
     * keys of the issuer (see [OutageReportingJwtDecoder]).
     */
    private fun failToken(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        if (exception is AuthenticationServiceException) {
            log.warn("A bearer token was not validated, because the issuer is not available: {}", exception.message)
            fail(request, response, IdentityException.IssuerUnavailable())
        } else {
            fail(request, response, IdentityException.InvalidToken())
        }
    }

    /**
     * Without the throw, a request that no resolver handles would get an empty response with status 200. The throw
     * makes the container send a 5xx, so a rejected request never looks like a success.
     */
    private fun fail(request: HttpServletRequest, response: HttpServletResponse, exception: IdentityException) {
        errors.resolveException(request, response, null, exception) ?: throw exception
    }

    /** Sessions end after `folio.ui.session-timeout`. Spring Boot's customizer runs first, so this one wins. */
    @Bean
    fun sessionTimeout(ui: UiProperties) = SessionRepositoryCustomizer<JdbcIndexedSessionRepository> {
        it.setDefaultMaxInactiveInterval(ui.sessionTimeout)
    }

    companion object {
        /** The client registration of the web UI. Its redirect URI is /login/oauth2/code/folio. */
        const val REGISTRATION_ID = "folio"

        /** The API routes. [ApiWithoutCookies] uses the same matcher. */
        internal val API = PathPatternRequestMatcher.pathPattern("/api/**")
        private val log = LoggerFactory.getLogger(SecurityConfiguration::class.java)
    }
}
