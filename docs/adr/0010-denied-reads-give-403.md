# 0010. Denied reads give 403, missing resources 404

Status: Accepted (2026-09-30)

## Context

A caller who may not view a resource can get 404, hiding that it exists, or 403. Hiding needs every
lookup to authorize before it reports a missing resource, and it makes access problems hard to diagnose.

## Decision

- Services look up the resource first. A missing resource gives 404.
- An existing resource the caller may not act on gives 403 for an authenticated caller and 401 for an
  anonymous one.
- Listings leave out children the caller may not view rather than failing.
- Seeing a namespace includes seeing its path: `NAMESPACE_VIEW` on a namespace allows reading its
  ancestors' slugs, even where the caller may not view those ancestors themselves.

## Consequences

- A caller can learn that an ID exists. IDs are uuidv7: time-ordered, with 74 random bits, so they cannot
  be guessed. A caller can only confirm an ID it already has; names and contents stay hidden.
- A grant deep in the tree reveals the names of the namespaces above it, which a path-addressed resource
  needs anyway (for example `configsets:resolve?path=…`).
- Error responses say which action was missing, which helps administrators fix grants.
- Switching to 404 later is a change in the services and the advice only.
