# 0026. Bouncy Castle as sshd's Ed25519 provider

Status: Accepted (2026-10-01)

## Context

Folio generates Ed25519 keys with the JDK (ADR 0003, ADR 0019). JGit 7.8's SSH transport uses Apache MINA sshd 2.19,
which supports EdDSA only through `net.i2p.crypto:eddsa` or Bouncy Castle; it has no JDK EdEC support. With neither on
the classpath, `SecurityUtils.isEDDSACurveSupported()` is false: Ed25519 client keys cannot authenticate and
`ssh-ed25519` host keys cannot be checked. v1-scope said "No Bouncy Castle" for crypto.

## Decision

- Add `org.bouncycastle:bcprov-jdk18on` 1.86 as a `runtimeOnly` dependency, so Folio's code cannot compile against it.
  sshd finds it through its own security-provider registrar.
- Folio's own crypto stays JDK-only: key generation, HKDF-SHA256 and AES-256-GCM. Bouncy Castle is not registered
  ahead of the JDK providers (no `Security.insertProviderAt(…, 1)`); a test checks that `KeyPairGenerator("Ed25519")`,
  `KDF("HKDF-SHA256")` and `Cipher("AES/GCM/NoPadding")` still resolve to `SunEC` and `SunJCE` after sshd has loaded
  its providers.
- At the SSH boundary, the source module converts the credential's JDK key pair to sshd's EdDSA key types through
  their standard encodings (X.509 and PKCS#8) with sshd's key factory, and zeroes Folio's copy of the PKCS#8 bytes.
- Startup fails if sshd reports no Ed25519 support.
- v1-scope's crypto row now reads: JDK only for Folio's own crypto; Bouncy Castle only as Apache sshd's Ed25519
  provider for Git SSH.

## Alternatives considered

- `net.i2p.crypto:eddsa` 0.3.0: what sshd prefers when present, but unmaintained since 2018.
- ECDSA P-256 keys, which sshd supports on the JDK: reverses the Ed25519 decision in v1-scope, ADR 0003 and ADR 0019
  and needs a migration path for existing keys.
- Folio's own JDK-based `EdDSASupport` for sshd: about fifteen methods against sshd internals, enabled through a
  JVM-wide system property, in security-critical code that sshd upgrades can break.

## Consequences

- A large dependency serves one purpose. Its updates matter for security; keep it current.
- Bouncy Castle's private-key objects are another copy of the key material that Folio cannot zero, like the JDK's
  (ADR 0019).
- sshd may also use Bouncy Castle for other SSH algorithms where it prefers it; that affects only the SSH transport.
