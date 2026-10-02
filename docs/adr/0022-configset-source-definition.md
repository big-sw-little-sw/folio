# 0022. ConfigSet source definition

Status: Accepted (2026-10-01)

## Context

v1-scope says each ConfigSet maps to exactly one Git source, and design 12.1 and 19.6 sketch a source with a
repository URI, a configured ref, a root path and a credential. Nothing says where the source lives, how the URI is
formed, which refs are allowed, or whether a source can change.

## Decision

- The source is part of the ConfigSet: `config_set` gains `credential_id`, `repository_path`, `branch` and
  `root_path`, all required. There is no separate `git_source` table and no source ID; one source per ConfigSet gives
  them the same lifetime.
- The source names a credential and a repository path on that credential's Git instance, such as `org/repo.git`. The
  host, port and SSH user come from the instance (ADR 0016, ADR 0025), so a source cannot point at a host its
  credential does not belong to. The SSH URL is built as `ssh://<user>@<host>:<port>/<repository path>`; it holds no
  secret.
- A repository path is one or more segments of letters, digits, `.`, `_` and `-`, joined by `/`, with no `.` or `..`
  segment and at most 500 characters. So it cannot carry a scheme, user, host or traversal.
- The configured ref is a branch only (`refs/heads/<branch>`), validated as a Git ref name. Tags and commit pins are
  not in v1.
- The root path is a relative path under the rules of ADR 0024; the empty string is the repository root.
- The source is set at creation and does not change in this slice. Changing it is an open question: it needs rules
  for the cache, sync state and revisions already served.
- The migration adds the columns as `not null` without defaults. Folio is pre-release, so a database that already holds
  ConfigSets fails this migration and must be recreated.

## Consequences

- Creating a ConfigSet needs a credential first, and the credential's authorization (ADR 0023).
- Moving a repository to another instance means a new ConfigSet until changing a source is designed.
- Adding tags or pinned commits later adds a column or a ref type; existing rows stay branches.
