# Folio

Folio serves configuration files from Git to applications, scripts, CLIs and agents. It puts the configuration in a
tree of namespaces, controls who can read and administer it, and serves files over HTTP at published revisions.

[docs/folio-design.md](docs/folio-design.md) is the single source of truth for scope, names and decisions. Read it
before you change the code. [AGENTS.md](AGENTS.md) gives the rules for code and text.

## Build

You need Java 25 and Docker. The integration tests start PostgreSQL 18 and an SSH Git server in Testcontainers.

```sh
./gradlew check          # ktlint, detekt, unit tests and integration tests
./gradlew test           # unit tests only, without Docker
./gradlew ktlintFormat   # format the Kotlin code
```

## Run locally

```sh
./gradlew bootTestRun
```

`bootTestRun` starts PostgreSQL, a mock OIDC provider and the SSH Git server in Testcontainers (design 17). It needs no
real identity provider and no real Git service.

1. Open http://localhost:8080 and select "Sign in". Enter any user name. To be a super admin, enter the claims
   `{"groups": ["folio-admins"]}`. Only a super admin can create the first Namespaces.
2. To call the API, read the issuer URI from the log. Get a token with the client credentials flow. The subject of the
   token is the client ID:
   `curl -d grant_type=client_credentials -d client_id=my-app -d client_secret=x -d scope=folio <issuer>/token`.
   The mock provider puts no application ID in the token, so a rule names this client as `user:my-app`.
3. To connect a repository, create a credential with the Git instance `test-server`. The log gives the command that
   authorizes its public key on the SSH Git server. Then connect `repos/sample.git` with the root path `config`.
4. The management port 8081 serves `/actuator/health` and `/actuator/prometheus`.

## Configure

`src/main/resources/application.yaml` lists each Folio setting with its default, and the settings that each deployment
must give: the issuer, the audiences, the UI client, the master keys, the Git cache directory and the Git instances.
Design section 15.3 lists the limits. Section 6 gives the setup for Entra ID and for Google Workspace.
