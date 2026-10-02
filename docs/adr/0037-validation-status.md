# 0037. Validation status

Status: Accepted (2026-10-02)

## Context

v1-scope asks for a validation status for YAML, JSON and properties files, as metadata: malformed files are still served
raw (design 15.1 to 15.3). Repository content is untrusted: validation must not execute it and must run with bounded
resources (design 23.3). Design 15.4 sketches a validator interface.

## Decision

- Statuses are design 15.2's `VALID`, `INVALID` and `UNKNOWN`. The format follows the file extension, ignoring case:
  `.yaml` and `.yml`, `.json`, `.properties`. Other files are `UNKNOWN`.
- Parsers already on the classpath; no new dependency:
  - YAML: SnakeYAML (through Spring Boot) composes the node graph of every document and constructs no objects. Its
    defaults reject custom global tags such as `!!java.net.URL`, more than 50 aliases to collections and nesting deeper
    than 50, so an alias bomb is `INVALID` at once. Local tags such as `!Ref` are only labels and stay `VALID`. Duplicate
    keys are accepted, since no mapping is built.
  - JSON: Jackson reads exactly one value; trailing content is `INVALID`. Its default stream constraints bound nesting.
  - Properties: `java.util.Properties.load`, which only rejects malformed `\uXXXX` escapes.
- Files over 3 MB, SnakeYAML's default input limit, are not validated: `UNKNOWN`.
- The status is computed when a file is read and sent as `X-Config-Validation-Status`. It is not in file listings:
  that would read and parse every file for every listing, or need a cache.
- One `ConfigFormat` enum holds the formats; there is no validator interface while the set is fixed.

## Consequences

- A YAML file with a custom global tag is `INVALID`: SnakeYAML rejects such tags because resolving them is how YAML
  loading executes code.
- Consumers who want the status of every file read each file.
- A format added later is one enum entry.
