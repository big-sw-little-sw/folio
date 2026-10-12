package io.github.big_sw_little_sw.folio

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** PostgreSQL 18 for integration tests and bootTestRun. Folio makes its IDs with `uuidv7()`, which is new in 18. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}
