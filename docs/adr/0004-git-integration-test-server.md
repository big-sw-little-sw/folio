# 0004. Git integration test server

Status: Accepted (2026-09-30)

## Context

Git source tests must exercise real SSH transport, including host-key verification and key
authentication. Third-party Git server images can change, disappear or carry more than tests need.

## Decision

Git integration tests run against an SSH Git server in Testcontainers. The test builds the image
with Testcontainers `ImageFromDockerfile` from a pinned `alpine` image plus `openssh` and `git`.
These tests are tagged `integration`.

## Consequences

- Tests do not depend on a third-party image.
- The test controls the server's host key and authorized keys.
- The first run builds the image, which adds time. The tests need Docker and run only in
  `integrationTest`.
