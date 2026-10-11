package io.github.big_sw_little_sw.folio.identity.internal

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
 * Sends an anonymous browser to sign-in when a UI page needs an authenticated caller (design 12.3). After sign-in,
 * Spring Security sends the browser back to the page from the saved request.
 *
 * It handles only `GET` requests outside `/api`. Other requests, and other failures, go on to the error advice. An
 * anonymous caller has no CSRF token, so a form post never gets here.
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
                request.method == HttpMethod.GET.name() &&
                !SecurityConfiguration.API.matches(request)
        if (!signInNeeded) return null
        savedRequests.saveRequest(request, response)
        return ModelAndView("redirect:/sign-in")
    }
}
