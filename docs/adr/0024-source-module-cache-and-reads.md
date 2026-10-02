# 0024. Source module, cache and reads

Status: Accepted (2026-10-01)

## Context

Slice 5 builds Git access over SSH: the onboarding check, a disposable bare-repository cache per source, and reads at
the latest or an exact commit beneath the root path. Slices 6 and 7 use it for sync and the consumption API. Spring
Modulith forbids cycles, and the design describes a `ConfigContentProvider` interface (design 12).

## Decision

- **Module.** A new module `source` (design 12, "Source abstraction") does Git access only. Dependencies point one way:
  `web` → `configset` → `source` → `credential` → `policy` → `security`. `credential` knows nothing of sources or
  ConfigSets. There is no `ConfigContentProvider` interface while Git is the only implementation; `SourceAccess` is the
  module's API. It does not authorize; the ConfigSet service does.
- **Cache.** `folio.git.cache-directory` (required, absolute) holds one bare repository per ConfigSet, named by its ID.
  Before a fetch, a repository that is missing, does not open, or cannot read its branch tip's commit and tree is
  deleted and created empty. A fetch that fails with an unclassified transport failure also deletes it, so the next
  fetch starts clean. Only the configured branch is fetched (`+refs/heads/b:refs/heads/b`, no tags).
- **Concurrency.** Fetches of one ConfigSet take an in-process lock; slice 6 adds leases across instances. Reads share
  the repository with a running fetch, which only adds objects and moves a ref. Deleting a repository waits for reads.
- **Reads.** `list` returns the regular files beneath the root path at `latest` (the branch tip as last fetched) or an
  exact commit ID (40 lowercase hex), as paths relative to the root path. `read` returns a regular file's raw bytes.
  Symlinks, submodules and directories are not files. Reads never contact the Git service: a commit the cache lacks,
  or a cache that does not exist, is `RevisionNotFoundException`, and the caller fetches.
- **Paths.** A path is relative and already normal: segments joined by `/`, none empty, `.` or `..`, and no backslash
  or NUL. Anything else is rejected, not rewritten. A read resolves the file path beneath the root path by joining
  segments, so it cannot leave the root path. Listed names that break these rules (Git allows a backslash) are left
  out, because they could not be read.
- **Timeout.** Each connection and every read from it time out after 30 seconds (design 23.2). This is a constant
  until operations show a need to tune it.

## Consequences

- A lost or damaged cache costs one full fetch and nothing else.
- Cache directories of deleted ConfigSets stay until removed by hand; slice 6 cleans them up (`plan.md`).
- A cache per ConfigSet duplicates objects when several ConfigSets read one repository; sharing is an optimisation
  for later.
- When a second source type arrives, the interface can be extracted from `SourceAccess`'s methods.
