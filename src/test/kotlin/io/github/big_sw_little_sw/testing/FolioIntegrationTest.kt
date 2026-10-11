package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import org.junit.jupiter.api.Tag
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

/**
 * A test of the full application through MockMvc, with PostgreSQL in Testcontainers and [TestIssuer] as the identity
 * provider. All classes with this annotation share one application context.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestIssuerConfiguration::class, TestRoutes::class)
annotation class FolioIntegrationTest

/**
 * A test of the full application on real ports. The management port is random, so it differs from the application
 * port, as in production. Metrics are on, so the Prometheus endpoint has content. [TestFilter] fails some requests
 * outside Spring MVC. All classes with this annotation share one application context.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@AutoConfigureMetrics
@Import(TestcontainersConfiguration::class, TestIssuerConfiguration::class, TestRoutes::class, TestFilter::class)
annotation class FolioServerTest
