package io.github.big_sw_little_sw.folio.consumption.web

import io.github.big_sw_little_sw.folio.consumption.RevisionSelector
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.TimeUnit

/**
 * Times consumption reads by route, revision kind and status (ADR 0039). Tags never name a ConfigSet or a path, so
 * their number stays bounded. The status is the one sent: Spring MVC maps exceptions to responses before
 * `afterCompletion`. Requests that Spring Security refuses before they reach the controller are not counted.
 */
@Component
class ReadMetrics(
    private val meters: MeterRegistry,
) : HandlerInterceptor,
    WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/configsets/*", "/api/v1/configsets/*/**")
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        request.setAttribute(STARTED, System.nanoTime())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        if (handler !is HandlerMethod || handler.beanType != ConsumptionController::class.java) return
        val route = ROUTES[handler.method.name] ?: return
        val started = request.getAttribute(STARTED) as? Long ?: return
        // A route without a revision parameter, such as metadata, describes the latest revision.
        val revision = request.getParameter("revision")
        val kind = if (revision == null || revision == RevisionSelector.LATEST) "latest" else "exact"
        Timer
            .builder("folio.consumption.reads")
            .tag("route", route)
            .tag("revision", kind)
            .tag("status", if (ex == null) statusTag(response.status) else "5xx")
            .register(meters)
            .record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
    }

    /** 200 and 304 exactly, other statuses by class, such as `4xx`. */
    private fun statusTag(status: Int) =
        when (status) {
            HttpStatus.OK.value(), HttpStatus.NOT_MODIFIED.value() -> status.toString()
            else -> "${status / STATUS_CLASS}xx"
        }

    private companion object {
        val STARTED = "${ReadMetrics::class.java.name}.started"
        const val STATUS_CLASS = 100

        /** Controller methods by route tag; a new route needs an entry here. */
        val ROUTES =
            mapOf("metadata" to "metadata", "files" to "listing", "file" to "file", "revisions" to "revisions")
    }
}
