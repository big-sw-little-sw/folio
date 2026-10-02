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
- **Anonymous callers.** URL rules let anonymous requests reach `/api/v1/configsets/**` and `configsets:resolve`; a
  token that is sent must still be valid. Services authorize: a denied anonymous caller gets 401 with
  `WWW-Authenticate: Bearer`, an authenticated one 403 (ADR 0010). By ID, a missing ConfigSet is 404 for everyone.
- **Anonymous resolve.** An anonymous caller gets the same 401 for a missing path and for a path it may not view, so it
  cannot probe which paths exist; authenticated callers keep 404 for both (ADR 0013). 401 rather than 404 because a
  token may help, and it is what anonymous callers get everywhere else.
- **Caching.**
  - Exact revisions: `max-age=31536000, immutable`. `latest`, metadata and the revision listing: `max-age` from
    `folio.consumption.latest-max-age` (default 30 seconds).
  - `public` when the rules would allow an anonymous caller the route's action on the ConfigSet
    (`PolicyService.isPublic`), otherwise `private`. Super admins never match an anonymous caller, so this is exactly
    "a `public` rule grants it".
  - ETags: a file's blob ID, which is content-addressed and so the same in every revision with the same bytes; a
    listing's commit ID, since the source cannot change (ADR 0022) and the root path's tree ID would add nothing;
    metadata a digest of its path and latest revision; the revision listing a digest of its newest entry and that
    entry's time, which every change to the listing moves. Spring answers a matching `If-None-Match` with 304 and the
    same headers.
  - `X-Config-Revision` on listings and file reads; `X-Config-Validation-Status` on file reads (ADR 0037).
- **Raw serving.** Bytes as stored. `application/yaml`, `application/json` and, for properties, `text/plain` by file
  extension, without a charset, since design 15.5 names none; anything else `application/octet-stream`. Spring
  Security's default `X-Content-Type-Options: nosniff` applies. File paths go through `SourcePath.parse`; Spring
  Security's firewall already rejects non-normalized paths and encoded slashes, backslashes, periods and NUL with 400.
- **Errors.** `ConsumptionException` subtypes: 400 for an invalid revision; 404 for a ConfigSet not synced yet and for
  unknown and unavailable revisions (ADR 0036). A failed on-demand fetch is `SourceAccessFailedException` with its code
  and fixed summary, 502 as in ADR 0027, but 504 for `DEADLINE_EXCEEDED`.

## Consequences

- Removing a `public` grant does not remove what shared caches already hold: exact revisions may stay there for a year.
- A file is read and validated before its ETag is compared, so a 304 costs a read. ADR 0037 bounds validation.
- Files are read into memory to serve them; only the repository size limit (ADR 0033) bounds them. Streaming large
  files is not built.
- Revisions beyond the newest 100 are not listed but stay readable by commit ID. Pagination is additive.
- An anonymous caller who mistypes a path in `resolve` gets 401, not 404.
