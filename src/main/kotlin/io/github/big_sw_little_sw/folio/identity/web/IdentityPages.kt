package io.github.big_sw_little_sw.folio.identity.web

import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.github.big_sw_little_sw.folio.identity.internal.SecurityConfiguration
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * The caller page and the sign-in route, which the layout links to. The caller page shows the subject, the groups and
 * the application ID of the caller. Thus a person can see which subjects a rule must name. The catalog serves the start
 * page.
 */
@Controller
class IdentityPages(private val currentCaller: CurrentCaller) {
    @GetMapping("/me")
    fun caller(model: Model): String {
        model.addAttribute("caller", currentCaller.get())
        return "identity/web/caller"
    }

    /**
     * A failed sign-in does not come back to this route, because a new sign-in could fail in the same way and loop.
     * Instead, the failed sign-in shows the error page with `SIGN_IN_FAILED`.
     */
    @GetMapping("/sign-in")
    fun signIn(): String = "redirect:$AUTHORIZATION_PATH"

    private companion object {
        const val AUTHORIZATION_PATH =
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI +
                "/" + SecurityConfiguration.REGISTRATION_ID
    }
}
