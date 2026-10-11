package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

/** The OpenAPI document covers the /api routes only, and Folio serves no Swagger UI (design 12.2). */
@FolioIntegrationTest
class OpenApiIntegrationTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `the OpenAPI document describes the API routes and not the UI routes`() {
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.paths['/api/test/caller']") { exists() }
            jsonPath("$.paths['/']") { doesNotExist() }
            jsonPath("$.paths['/sign-in']") { doesNotExist() }
            jsonPath("$.paths['/test/notice']") { doesNotExist() }
        }
    }

    @Test
    fun `Folio serves no Swagger UI`() {
        mvc.get("/swagger-ui/index.html").andExpect { status { isNotFound() } }
    }
}
