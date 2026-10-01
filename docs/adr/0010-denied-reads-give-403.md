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

## Consequences

- A caller can learn that an ID exists. IDs are random UUIDs, so this only confirms an ID the caller
  already has; names and contents stay hidden.
- Error responses say which action was missing, which helps administrators fix grants.
- Switching to 404 later is a change in the services and the advice only.
