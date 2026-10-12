package io.github.big_sw_little_sw.folio.identity.internal

import io.github.big_sw_little_sw.folio.identity.IdentityException
import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Sends an anonymous browser to sign-in when a UI page needs an authenticated caller. After sign-in, Spring Security
 * sends the browser back to the page from the saved request.
 *
 * This resolver handles only `GET` requests outside `/api`. The error advice handles all other requests and failures.
 * An anonymous caller has no CSRF token, so the CSRF filter rejects a form post before it gets to this resolver. A
 * failed sign-in shows its error page, because a new sign-in could fail in the same way and loop.
 */
@Configuration(proxyBeanMethods = false)
class SignInRedirect :
    WebMvcConfigurer,
    HandlerExceptionResolver {
    private val savedRequests = HttpSessionRequestCache()

    override fun extendHandlerExceptionResolvers(resolvers: MutableList<HandlerExceptionResolver>) {
        resolvers.add(0, this)
    }

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        exception: Exception,
    ): ModelAndView? {
        val signInNeeded =
            exception is FolioException &&
                exception.kind == ErrorKind.UNAUTHENTICATED &&
                exception !is IdentityException.SignInFailed &&
                request.method == HttpMethod.GET.name() &&
                !SecurityConfiguration.API.matches(request)
        if (!signInNeeded) return null
        savedRequests.saveRequest(request, response)
        return ModelAndView("redirect:/sign-in")
    }
}
