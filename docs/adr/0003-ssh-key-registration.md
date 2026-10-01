# 0003. SSH key registration

Status: Accepted (2026-09-30)

## Context

Folio generates SSH key pairs on the server. This only works if each Git instance accepts public
keys registered by an administrator. If keys had to be issued centrally, importing existing private
keys would move into v1. `v1-scope.md` listed this as an open question.

## Decision

Folio generates Ed25519 key pairs. An administrator registers the public key in one of two ways:

- as a repository deploy key (GitHub, GitLab, Bitbucket, Gitea), or
- on a bot account's SSH keys. Azure DevOps needs this because it has no deploy keys.

## Consequences

- Both options work with the same code. Folio does not know which one was used.
- Importing existing private keys stays out of v1.
- A bot-account key grants whatever the bot account can access. Administrators scope that account.
