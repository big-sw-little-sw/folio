package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import org.junit.jupiter.api.Tag
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A test of the full application through MockMvc, with PostgreSQL in Testcontainers and [TestIssuer] as the identity
 * provider. [HtmlCheckConfiguration] examines each HTML response. [TestCatalog] builds catalog trees, [TestCredentials]
 * sets up credentials, and [TestAudit] reads audit records. [InMemorySource] replaces the Git adapter
 * ([InMemorySourceOnly]), and tests publish revisions with it. [TestLocks] runs tasks while another transaction holds a
 * lock. [NoSyncSchedule] stops the scheduled polls and sweeps. All classes with this annotation share one application
 * context and one database. Each test must read only the data that it made, because data from other tests stays in the
 * database.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    TestIssuerConfiguration::class,
    TestRoutes::class,
    HtmlCheckConfiguration::class,
    TestCatalog::class,
    TestCredentials::class,
    InMemorySource::class,
    InMemorySourceOnly::class,
    NoSyncSchedule::class,
    TestAudit::class,
    TestLocks::class,
)
annotation class FolioIntegrationTest

/**
 * A test of the `git` module and of reads through the Git adapter. The context has the Git adapter and [SshGitServer]
 * as its Git instance. [TestGit] connects repositories and syncs them, and [TestSyncInstances] builds another instance.
 * The tests run polls, syncs and sweeps themselves ([NoSyncSchedule]). The other fixtures are the fixtures of
 * [FolioIntegrationTest]. All classes with this annotation share one application context. The file limit is 1 KB, so a
 * test can push a file above the limit quickly.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@SpringBootTest(properties = ["folio.content.max-file-size=1KB"])
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    TestIssuerConfiguration::class,
    SshGitServerConfiguration::class,
    HtmlCheckConfiguration::class,
    TestCatalog::class,
    TestCredentials::class,
    TestGit::class,
    TestSyncInstances::class,
    NoSyncSchedule::class,
    TestAudit::class,
    TestLocks::class,
)
annotation class GitIntegrationTest

/**
 * This annotation marks a test of the full application on real ports. The management port is random, so it differs
 * from the application port, as in production. Metrics are on, so the Prometheus endpoint has content. [TestFilter]
 * fails some requests outside Spring MVC. All classes with this annotation share one application context.
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

private val client = HttpClient.newHttpClient()

/** Sends a GET without cookies to [path] on the [FolioServerTest] port [port]. */
fun httpGet(port: Int, path: String): HttpResponse<String> = client.send(
    HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build(),
    HttpResponse.BodyHandlers.ofString(),
)
