package io.github.big_sw_little_sw.folio.web.internal

import gg.jte.ContentType
import gg.jte.TemplateEngine
import io.github.big_sw_little_sw.folio.web.PageContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationTrustResolverImpl
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.support.RequestContextUtils

@Configuration(proxyBeanMethods = false)
class WebConfiguration : WebMvcConfigurer {
    /**
     * The build compiles the templates into classes in [TEMPLATE_PACKAGE] (see `jte` in build.gradle.kts). The jte
     * starter finds precompiled templates only in its default package. Thus Folio makes its own engine.
     */
    @Bean
    fun jteTemplateEngine(): TemplateEngine =
        TemplateEngine.createPrecompiled(null, ContentType.Html, null, TEMPLATE_PACKAGE)

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(PageContextInterceptor)
    }

    private companion object {
        const val TEMPLATE_PACKAGE = "io.github.big_sw_little_sw.folio"
    }
}

/**
 * Spring Security gives an anonymous caller an authentication too. The resolver treats that authentication as not
 * signed in.
 */
private val trustResolver = AuthenticationTrustResolverImpl()

/** Adds the [PageContext] to each model that a template renders. JSON responses and redirects render no template. */
private object PageContextInterceptor : HandlerInterceptor {
    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        if (modelAndView != null && modelAndView.viewName?.startsWith("redirect:") == false) {
            modelAndView.addObject("page", pageContextOf(request))
        }
    }
}

/**
 * Reads the caller from the security context, not from the request. The context has the caller also before Spring
 * Security wraps the request, for example when the CSRF filter rejects a request. The principal name of a signed-in
 * caller is its subject. The first read of the CSRF token stores the token in the session, so only a signed-in caller
 * reads it.
 */
fun pageContextOf(request: HttpServletRequest): PageContext {
    val authentication = SecurityContextHolder.getContext().authentication
    val signedInAs = authentication?.takeIf { trustResolver.isAuthenticated(it) }?.name
    return PageContext(
        signedInAs = signedInAs,
        notice = RequestContextUtils.getInputFlashMap(request)?.get(PageContext.NOTICE) as? String,
        csrf = signedInAs?.let { request.getAttribute(CsrfToken::class.java.name) as? CsrfToken },
    )
}
