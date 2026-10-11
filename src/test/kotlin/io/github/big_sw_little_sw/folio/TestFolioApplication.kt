package io.github.big_sw_little_sw.folio

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/**
 * Runs Folio for local development with `./gradlew bootTestRun`. Testcontainers starts PostgreSQL and a mock OIDC
 * provider, so no real identity provider is necessary.
 *
 * - UI: open http://localhost:8080, select "Sign in", and enter any user name. Optional claims JSON, for example
 *   `{"groups": ["team-a"]}`, gives groups. The group `folio-admins` makes the caller a super admin, who can create
 *   the first Namespaces (src/test/resources/config/application.yaml).
 * - API: the log shows the issuer URI. Get a token with the client credentials flow and the scope `folio`, which
 *   becomes the audience:
 *   `curl -d grant_type=client_credentials -d client_id=my-app -d client_secret=x -d scope=folio <issuer>/token`.
 *   The subject of the token is the client ID.
 */
fun main(args: Array<String>) {
    fromApplication<FolioApplication>()
        .with(TestcontainersConfiguration::class, MockOidcProviderConfiguration::class)
        .run(*args)
}
