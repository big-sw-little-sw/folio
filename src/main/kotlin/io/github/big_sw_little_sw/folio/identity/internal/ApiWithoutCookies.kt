package io.github.big_sw_little_sw.folio.identity.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Hides the cookies of API requests. The API accepts only bearer tokens, never the session cookie (design 6.2).
 *
 * Spring Session runs before the filter chains, so `STATELESS` does not stop it. Without this filter, an API request
 * with the session cookie would load the UI session and keep it alive. Thus this filter runs before Spring Session.
 */
@Component
class ApiWithoutCookies :
    OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = SessionRepositoryFilter.DEFAULT_ORDER - 1

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !SecurityConfiguration.API.matches(request)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        chain.doFilter(
            object : HttpServletRequestWrapper(request) {
                override fun getCookies(): Array<Cookie>? = null
            },
            response,
        )
    }
}
