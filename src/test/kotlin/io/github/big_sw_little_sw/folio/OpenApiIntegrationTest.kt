package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.*
import org.hamcrest.Matchers.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*
import kotlin.test.*

@FolioIntegrationTest
class OpenApiIntegrationTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `the OpenAPI document describes the API routes and not the UI routes`() {
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.paths['/api/test/caller']") { exists() }
            jsonPath("$.paths['/api/v1/configsets:resolve'].get") { exists() }
            jsonPath("$.paths['/api/v1/admin/namespaces'].post") { exists() }
            jsonPath("$.paths['/api/v1/admin/namespaces/{id}'].patch") { exists() }
            jsonPath("$.paths['/api/v1/admin/configsets/{id}:move'].post") { exists() }
            jsonPath("$.paths['/api/v1/admin/{collection}/{id}/rules/{action}'].put") { exists() }
            jsonPath("$.paths['/api/v1/admin/{collection}/{id}/explain'].post") { exists() }
            jsonPath("$RULES.get.parameters[?(@.name == 'collection')].schema.enum[*]") {
                value(contains("namespaces", "configsets"))
            }
            jsonPath("$.paths['/api/v1/configsets/{id}'].get") { exists() }
            jsonPath("$.paths['/api/v1/configsets/{id}/revisions'].get") { exists() }
            jsonPath("$.paths['/api/v1/configsets/{id}/files'].get") { exists() }
            jsonPath("$FILE.get.parameters[?(@.name == 'path')].in") { value(contains("path")) }
            jsonPath("$FILE.get.responses['200'].content['*/*'].schema.format") { value("binary") }
            jsonPath("$FILE.get.responses['200'].headers['X-Folio-Validation-Status']") { exists() }
            jsonPath("$FILE.get.responses['200'].headers['X-Folio-Revision']") { exists() }
            jsonPath("$FILE.get.responses['200'].headers['ETag']") { exists() }
            jsonPath("$FILE.get.responses['304']") { exists() }
            // OpenAPI path templates support only {name}. OpenApiConfiguration renames each {*name} variable of Spring
            // MVC, so no path in the document keeps one.
            jsonPath("$.paths.keys()") { value(everyItem(not(containsString("{*")))) }
            jsonPath("$.paths['/']") { doesNotExist() }
            jsonPath("$.paths['/sign-in']") { doesNotExist() }
            jsonPath("$.paths['/namespaces/{id}']") { doesNotExist() }
            jsonPath("$.paths['/test/notice']") { doesNotExist() }
        }
    }

    @Test
    fun `Folio serves no Swagger UI`() {
        mvc.get("/swagger-ui/index.html").andExpect { status { isNotFound() } }
    }

    private companion object {
        /**
         * One route serves the rules of Namespaces and of ConfigSets, so its `collection` path variable has two values.
         */
        const val RULES = "$.paths['/api/v1/admin/{collection}/{id}/rules']"

        /** The route that serves the raw bytes of a file. Its path variable can contain '/'. */
        const val FILE = "$.paths['/api/v1/configsets/{id}/files/{path}']"
    }
}
