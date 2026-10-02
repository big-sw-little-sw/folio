# 0025. SSH host keys and isolation from the environment

Status: Accepted (2026-10-01). Amends ADR 0016.

## Context

Folio connects to Git services with stored private keys. CLAUDE.md and design 13.4 require mandatory host-key
verification. JGit's SSH client by default reads `~/.ssh/config`, `~/.ssh/known_hosts` and default identities, asks an
SSH agent, and reads the system and user Git config. Any of these would let the server account's files change what
Folio trusts or which key it offers.

## Decision

- **Instance configuration.** Each entry in `folio.git.instances` gains `user` (default `git`) and `host-keys`, a
  required, non-empty list of OpenSSH public key lines (`ssh-ed25519 AAAA… [comment]`). Hosts and users are validated
  so they cannot change the SSH URL. The credential module binds them and exposes `GitInstances`; it does no SSH
  parsing.
- **Parsing.** The source module parses every host key line at startup with sshd's key parser. A bad line fails
  startup with `folio.git.instances.<name>.host-keys[<index>]`, never the key itself.
- **Verification.** Each connection gets a key database that trusts exactly the configured keys of the credential's
  instance. Unknown and changed keys are rejected, never added; there is no accept-new and no fallback to files. Keys
  are compared with sshd's `KeyUtils.compareKeys`, not `equals`, because key classes differ between providers. A
  rejection is recorded so the failure reports as `HOST_KEY_REJECTED` (ADR 0027).
- **Isolation.** The session factory is built per connection with: a home and `.ssh` directory that are an empty
  directory under the cache directory; no SSH config (`setConfigStoreFactory` returns null); no default identities or
  known_hosts files; the credential's key as the only key; `publickey` as the only authentication method; and the
  agent connector factory set to null. A process-wide JGit `SystemReader` returns empty system, user and JGit config,
  and never saves them.
- **Logging.** JGit and Apache sshd loggers are off in `application.yaml`, because their messages can quote server
  output. Folio logs only the ConfigSet ID and failure code.

## Consequences

- An integration test proves that a host key, an authorized private key and an SSH config placed in the SSH home
  directory are ignored.
- Rotating a Git service's host key needs a configuration change and redeploy before the server switches, or sync
  fails with `HOST_KEY_REJECTED`. Listing both the old and new keys covers the switch.
- Host-key certificates (`@cert-authority`) are not supported.
- Debugging a transport problem means turning the JGit or sshd loggers on, which can write transport output to the
  logs.
