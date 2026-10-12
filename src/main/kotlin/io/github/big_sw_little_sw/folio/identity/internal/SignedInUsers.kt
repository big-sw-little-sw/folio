package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.Caller
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser

/**
 * The principal of a browser session. It keeps the caller that sign-in made, so a change of groups or of
 * `folio.super-admins` takes effect at the next sign-in. Spring Session stores the principal in PostgreSQL with Java
 * serialization. [Caller] is not serializable, so this class keeps plain fields.
 *
 * The principal name is the subject of the caller, as for [BearerAuthentication], so the layout shows the subject that
 * rules name.
 */
class SignedInUser(user: OidcUser, caller: Caller.Authenticated) :
    DefaultOidcUser(user.authorities, user.idToken, user.userInfo) {
    private val subject = caller.subject
    private val groups = LinkedHashSet(caller.groups)
    private val applicationId = caller.applicationId

    // Java serialization reads this field as false from a session that is older than the field. The caller of that
    // session is then no super admin until the next sign-in. A missing status thus gives less access, never more.
    private val superAdmin = caller.superAdmin

    val caller: Caller.Authenticated
        get() = Caller.Authenticated(subject, groups, applicationId, superAdmin)

    override fun getName(): String = subject

    private companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Makes the [SignedInUser] from the ID token at sign-in. Failures of [CallerFactory.toCaller] stop the sign-in, and the
 * failure handler of sign-in logs them.
 */
class SignedInUserService(private val callerFactory: CallerFactory) : OAuth2UserService<OidcUserRequest, OidcUser> {
    private val delegate = OidcUserService()

    override fun loadUser(request: OidcUserRequest): OidcUser {
        val user = delegate.loadUser(request)
        return SignedInUser(user, callerFactory.toCaller(user.idToken.claims))
    }
}

/**
 * Folio uses only the ID token at sign-in, so Folio keeps no access or refresh token of the identity provider. The
 * default repository of Spring would keep these tokens in memory after the session ends.
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
