package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.folio.identity.internal.SignedInUser
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.time.Duration
import java.time.Instant

/** A MockMvc request in a browser session of a person who signed in to the UI as [caller]. */
fun signedIn(caller: Caller.Authenticated): RequestPostProcessor {
    val now = Instant.now()
    val idToken = OidcIdToken("id-token", now, now + Duration.ofMinutes(5), mapOf("sub" to caller.subject))
    val user = SignedInUser(DefaultOidcUser(emptyList(), idToken, "sub"), "sub", caller)
    return authentication(OAuth2AuthenticationToken(user, user.authorities, "folio"))
}
