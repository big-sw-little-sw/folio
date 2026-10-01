# 0005. detekt pre-release

Status: Accepted (2026-09-30)

## Context

The project uses Kotlin 2.3. detekt 1.23.8, the latest stable release, is compiled for Kotlin 2.0
and does not support Kotlin 2.3. detekt must run with the Kotlin version it was compiled with. The
Spring dependency-management plugin otherwise forces the project's Kotlin version onto the `detekt`
configuration.

## Decision

- Use detekt 2.0.0-alpha.6.
- The `detekt` Gradle configuration pins Kotlin to `detektKotlin` from the version catalog.

## Consequences

- The build depends on a pre-release tool. Rule names and configuration may change before 2.0.0.
- Move to detekt 2.0.0 final when it is released, and bump `detektKotlin` with it.
