package io.github.big_sw_little_sw.folio.web.internal

import io.swagger.v3.oas.models.Paths
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * SDKs and the `folio` CLI will use the OpenAPI document as their contract (design 12.2). Spring MVC writes a path
 * variable that matches the rest of the path as `{*name}`. OpenAPI path templates support only `{name}`, so this
 * customizer renames each such variable. Without the rename, OpenAPI tools cannot read the template. The renamed
 * template does not show that the value can contain `/`. Thus the parameter description of the route must state it.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun pathTemplates(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.paths?.let { paths ->
            val renamed = Paths()
            paths.forEach { (template, item) -> renamed.addPathItem(template.replace(CAPTURE, "{$1}"), item) }
            renamed.extensions = paths.extensions
            openApi.paths = renamed
        }
    }

    private companion object {
        val CAPTURE = Regex("""\{\*(\w+)}""")
    }
}
