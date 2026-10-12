package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.*
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Starts the SSH Git server of the tests for local runs. The server has the repository `repos/sample.git` with the
 * root path `config`. The log shows the command that authorizes the public key of a credential.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SshGitServerConfiguration::class)
class LocalGitServerConfiguration {
    @Bean
    fun localGitServerLog() = ApplicationRunner {
        SshGitServer.createRepository("sample")
        LoggerFactory.getLogger(SshGitServer::class.java).info(
            "Git instance: test-server. Repository path: repos/sample.git. Root path: config. " +
                "Authorize the public key of a credential with: {}",
            SshGitServer.authorizeCommand,
        )
    }
}
