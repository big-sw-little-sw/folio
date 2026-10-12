package io.github.big_sw_little_sw.folio

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/**
 * Runs Folio for local development with `./gradlew bootTestRun`. Testcontainers starts PostgreSQL, a mock OIDC
 * provider and an SSH Git server, so no real identity provider and no real Git service are necessary.
 *
 * - UI: open http://localhost:8080, select "Sign in", and enter any user name. To give groups, enter claims as JSON,
 *   for example `{"groups": ["team-a"]}`. The group `folio-admins` makes the caller a super admin. Only a super admin
 *   can create the first Namespaces (src/test/resources/config/application.yaml).
 * - API: the log shows the issuer URI. Get a token with the client credentials flow and the scope `folio`. The
 *   provider uses the scope as the audience:
 *   `curl -d grant_type=client_credentials -d client_id=my-app -d client_secret=x -d scope=folio <issuer>/token`.
 *   The subject of the token is the client ID.
 * - Git: create a credential with the Git instance `test-server`. The log shows the command that authorizes its public
 *   key on the SSH Git server. Then connect the repository `repos/sample.git` with the root path `config`.
 */
fun main(args: Array<String>) {
    fromApplication<FolioApplication>()
        .with(
            TestcontainersConfiguration::class,
            MockOidcProviderConfiguration::class,
            LocalGitServerConfiguration::class,
        ).run(*args)
}
