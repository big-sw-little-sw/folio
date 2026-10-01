# 0013. ConfigSet paths and slugs

Status: Accepted (2026-09-30)

## Context

Design section 7 makes paths for discovery and stable IDs for references, and
`GET /api/v1/configsets:resolve?path=…` turns a path into a ConfigSet. Namespaces and ConfigSets live in
separate tables. Nothing says whether a namespace and a ConfigSet may share a slug under the same parent,
how a path is written, or which slug rules ConfigSets follow.

## Decision

- A ConfigSet's path is its namespace's path plus its own slug, for example `engineering/ai/service-a`.
  When resolving, the last segment is always the ConfigSet slug and the segments before it are namespaces.
- A namespace and a ConfigSet may therefore share a slug under the same parent; the path is not ambiguous.
  Slugs are unique among sibling namespaces (slice 1) and among the ConfigSets of one namespace. There is no
  uniqueness check across the two tables.
- A ConfigSet always lives in a namespace, so a path has at least two segments.
- The text form has no leading or trailing slash. A leading or trailing slash, an empty segment, fewer than
  two segments or a segment that is not a valid slug gives 400. Resolve returns the path in this form.
- ConfigSets use the namespace module's `Slug` type and rules. An invalid ConfigSet slug fails with
  `InvalidSlugException`, whose problem detail (`Invalid slug '…'`) does not name a resource type and reads
  correctly for both. An invalid path fails with `InvalidConfigSetPathException` instead, so the client sees
  the whole path.
- Seeing a ConfigSet includes seeing its path, as for namespaces (ADR 0010).
- Resolve looks the path up first: a path that leads nowhere gives 404 and an existing ConfigSet the caller
  may not view gives 403 (ADR 0010).

## Consequences

- Renaming or moving a namespace changes the paths of the ConfigSets below it; their IDs do not change, and
  their old paths stop resolving.
- Accepting a leading slash later is additive.
- Unlike IDs, paths can be guessed. A caller with a token can confirm that a guessed path names a ConfigSet,
  because it gets 403 rather than 404. Its contents stay hidden. Switching resolve to 404 for denied callers
  is a change in `ConfigSetService.resolve` only.
- If ConfigSet slugs ever need different rules, `Slug` moves or splits; the column already allows 100
  characters like namespaces.
