package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser

/**
 * The principal of a browser session. It keeps the caller that sign-in made, so a change of groups takes effect at
 * the next sign-in (design 6.2). Spring Session stores it in PostgreSQL with Java serialization. Thus it keeps plain
 * fields, not a [Caller].
 */
class SignedInUser(user: OidcUser, subjectClaim: String, caller: Caller.Authenticated) :
    DefaultOidcUser(user.authorities, user.idToken, user.userInfo, subjectClaim) {
    private val subject = caller.subject
    private val groups = LinkedHashSet(caller.groups)
    private val applicationId = caller.applicationId

    val caller: Caller.Authenticated
        get() = Caller.Authenticated(subject, groups, applicationId)

    private companion object {
        private const val serialVersionUID = 1L
    }
}

/** Makes the [SignedInUser] from the ID token at sign-in. A token that gives no caller stops the sign-in. */
class SignedInUserService(private val callerClaims: CallerClaims, private val subjectClaim: String) :
    OAuth2UserService<OidcUserRequest, OidcUser> {
    private val delegate = OidcUserService()

    override fun loadUser(request: OidcUserRequest): OidcUser {
        val user = delegate.loadUser(request)
        val caller =
            try {
                callerClaims.toCaller(user.idToken.claims)
            } catch (exception: InvalidClaims) {
                log.warn("Sign-in refused: {}", exception.message)
                throw OAuth2AuthenticationException(OAuth2Error("invalid_id_token"), exception)
            }
        return SignedInUser(user, subjectClaim, caller)
    }

    private companion object {
        val log = LoggerFactory.getLogger(SignedInUserService::class.java)
    }
}

/**
 * Folio uses only the ID token at sign-in, so it keeps no access or refresh token of the identity provider. Spring's
 * default would keep them in memory after the session ends.
 */
object NoAuthorizedClients : OAuth2AuthorizedClientRepository {
    override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication,
        request: HttpServletRequest,
    ): T? = null

    override fun saveAuthorizedClient(
        authorizedClient: OAuth2AuthorizedClient,
        principal: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = Unit

    override fun removeAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = Unit
}
