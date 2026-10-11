package io.github.big_sw_little_sw.folio.identity.web

import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.github.big_sw_little_sw.folio.identity.internal.SecurityConfiguration
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** The start page, which shows the caller, and the sign-in route that the layout links to. */
@Controller
class IdentityPages(private val currentCaller: CurrentCaller) {
    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("caller", currentCaller.get())
        return "identity/web/home"
    }

    @GetMapping("/sign-in")
    fun signIn(): String = "redirect:$AUTHORIZATION_PATH"

    /** Spring Security sends a failed sign-in here. A new redirect could repeat the failure, so a page explains it. */
    @GetMapping("/sign-in", params = ["error"])
    fun signInFailed(): String = "identity/web/signInFailed"

    private companion object {
        const val AUTHORIZATION_PATH =
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI +
                "/" + SecurityConfiguration.REGISTRATION_ID
    }
}
