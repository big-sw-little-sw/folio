# 0006. Expected failures as module exceptions

Status: Accepted (2026-09-30)

## Context

`CLAUDE.md` allows expected failures (not found, duplicate slug, rejected move) as sealed result types
or as specific exceptions. Namespaces are the first module, and later modules will copy its choice.
Mixing both styles would give callers two ways to handle the same kind of failure.

## Decision

- Each module declares a sealed exception base class in its public API, for example
  `NamespaceException`, with one subclass per expected failure. Subclasses carry the IDs and values
  an error response needs.
- Application services and domain types throw these exceptions. They do not return result types.
- A unique-constraint violation that has a domain meaning is translated to the matching exception in
  the repository that owns the constraint.
- The HTTP layer maps them to responses in one `@RestControllerAdvice`.

## Consequences

- A thrown exception rolls back the `@Transactional` service method, so a failure after a partial
  write needs no extra handling.
- Service signatures stay plain (`fun move(...): Namespace`); callers that do not handle a failure
  let it reach the controller advice.
- The compiler does not force callers to handle failures. Tests cover each failure instead.
