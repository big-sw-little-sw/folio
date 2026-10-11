package io.github.big_sw_little_sw.folio.identity.web

import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.github.big_sw_little_sw.folio.identity.internal.SecurityConfiguration
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * The caller page and the sign-in route that the layout links to. The caller page shows the subject, the groups and the
 * application ID, which rules name. The catalog serves the start page.
 */
@Controller
class IdentityPages(private val currentCaller: CurrentCaller) {
    @GetMapping("/me")
    fun caller(model: Model): String {
        model.addAttribute("caller", currentCaller.get())
        return "identity/web/caller"
    }

    @GetMapping("/sign-in")
    fun signIn(): String = "redirect:$AUTHORIZATION_PATH"

    /** Spring Security sends a failed sign-in here. A page explains the failure, because a new redirect could loop. */
    @GetMapping("/sign-in", params = ["error"])
    fun signInFailed(): String = "identity/web/signInFailed"

    private companion object {
        const val AUTHORIZATION_PATH =
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI +
                "/" + SecurityConfiguration.REGISTRATION_ID
    }
}
