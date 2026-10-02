# 0035. Consumption API

Status: Accepted (2026-10-02). Amends ADR 0013, ADR 0027 and ADR 0032.

## Context

Slice 7 builds the consumer-facing API of design 16: ConfigSet metadata, file listings, raw reads and the revision
listing, at `latest` or an exact revision, with HTTP caching. Anonymous callers must reach it so that `public` rules
apply. Design 16 gives paths, but not the module, the actions, how `public` rules meet caching, or what an anonymous
caller gets from `resolve`.

## Decision

- **Module.** A new module `consumption` depends on `configset`, `sync`, `source` and `policy`; only `web` depends on
  it. `ConsumptionService` authorizes through `ConfigSetService.requireAllowed` and reads through `SourceAccess` and
  `SyncedRevisions` (ADR 0036), which no HTTP layer may use.
- **Routes**, outside `/admin`:
  - `GET /api/v1/configsets/{id}`: `{id, path, latestRevision}`. Needs `CONFIG_SET_VIEW`.
  - `GET /api/v1/configsets/{id}/files?revision=…`: `{revision, files: [{path, size}]}`. Needs `CONFIG_ITEM_READ`.
  - `GET /api/v1/configsets/{id}/files/{*path}?revision=…`: the raw bytes. Needs `CONFIG_ITEM_READ`.
  - `GET /api/v1/configsets/{id}/revisions`: `{revisions: [{id, syncedAt}]}`, newest first, at most 100, not
    paginated. Needs `CONFIG_VERSION_LIST`.
  - `revision` is `latest` (the default) or a commit ID of 40 lowercase hex characters; anything else is 400.
    `GET …/revisions/{id}` and bundles are not built.
- **Actions.** `CONFIG_ITEM_READ` and `CONFIG_VERSION_LIST` from design 9.1. `CONFIG_ITEM_READ` covers `latest` and every
  synced revision. `CONFIG_VERSION_READ` is not added: nothing in v1 needs to grant `latest` without history.
- **Anonymous callers.** URL rules let anonymous `GET` and `HEAD` requests reach `/api/v1/configsets/**` and
  `configsets:resolve`; other methods there still need a token, and a token that is sent must be valid. Services
  authorize: a denied anonymous caller gets 401 with `WWW-Authenticate: Bearer`, an authenticated one 403 (ADR 0010). By
  ID, a missing ConfigSet is 404 for everyone.
- **Anonymous resolve.** An anonymous caller gets the same 401 for a missing path, a path below a missing namespace and
  a path it may not view, so it cannot probe which paths exist; authenticated callers keep 404 for all (ADR 0013). 401
  rather than 404 because a token may help, and it is what anonymous callers get everywhere else. The answers differ in
  timing only: a path whose namespaces exist costs a few more queries. That leaks which namespace paths exist to a
  caller who measures response times, and is accepted.
- **Caching.**
  - A ConfigSet's source never changes (ADR 0022), so an exact revision's listing and files never change either. That is
    what makes the year-long caching and the listing's entity tag sound; changing a source would need both revisited.
  - Exact revisions: `max-age=31536000, immutable`. When public, also `s-maxage` of `folio.consumption.latest-max-age`:
    shared caches revalidate, and so start getting 401 soon after a `public` grant is removed, while the caller's own
    cache keeps the year.
  - `latest`, metadata and the revision listing: `max-age` from `folio.consumption.latest-max-age` (default 30 seconds).
  - `public` when the rules would allow an anonymous caller the route's action on the ConfigSet, including through a
    namespace rule (`PolicyService.isPublic`); otherwise `private`. Super admins never match an anonymous caller.
  - Error responses keep Spring Security's default `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`.
  - ETags: a file's blob ID, which is content-addressed; a listing's commit ID; metadata a digest of its path and latest
    revision; the revision listing a digest of its newest entry and that entry's time, which every change to the listing
    moves. Authorization runs first, so a matching `If-None-Match` from a caller without the action gets 401 or 403.
    For files, the service compares `If-None-Match` with the blob ID before loading the file, so a 304 loads and
    validates nothing; for the other routes Spring compares it with the ETag and answers 304 with the same headers.
  - `X-Config-Revision` on listings and file reads; `X-Config-Validation-Status` on file reads (ADR 0037).
- **Raw serving.** Bytes as stored. `application/yaml`, `application/json` and, for properties, `text/plain` by file
  extension, without a charset, since design 15.5 names none; anything else `application/octet-stream`. Spring
  Security's default `X-Content-Type-Options: nosniff` applies. File paths go through `SourcePath.parse`; Spring
  Security's firewall already rejects non-normalized paths and encoded slashes, backslashes, periods and NUL with 400.
- **File size.** A file larger than `folio.consumption.max-file-size` (default 10 MB) is refused with 422 before it is
  loaded: its size comes from the object header. 422 rather than 413, which is about the request's content; the request
  is fine, Folio declines to serve the file. The source module raises it (`SourceFileTooLargeException`) because that is
  where the bytes are loaded, including JGit's `LargeObjectException`.
- **Errors.** `ConsumptionException` subtypes: 400 for an invalid revision; 404 for a ConfigSet not synced yet and for
  unknown and unavailable revisions (ADR 0036); 503 with `Retry-After` when an on-demand fetch cannot run now
  (ADR 0036). A failed on-demand fetch is `SourceAccessFailedException` with its code and fixed summary, 502 as in
  ADR 0027, but 504 for `DEADLINE_EXCEEDED`.

## Consequences

- A removed `public` grant reaches shared caches within `latest-max-age`; private caches of callers who could read the
  file keep it for up to a year.
- Memory for one read is bounded by `folio.consumption.max-file-size`; streaming is not built.
- Revisions beyond the newest 100 are not listed but stay readable by commit ID. Pagination is additive.
- An anonymous caller who mistypes a path in `resolve` gets 401, not 404.
