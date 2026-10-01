# 0019. Private-key encryption at rest

Status: Accepted (2026-10-01)

## Context

v1-scope fixes the scheme: Ed25519 keys from the JDK, AES-256-GCM under a key derived by HKDF-SHA256 from a
master secret, a fresh 32-byte salt and a fixed context label, a fresh 96-bit nonce, associated data
`credentialId | keyId | masterKeyVersion`, and a versioned master-key ring from configuration. It does not fix
the byte encodings, the label, the version format or the column layout.

## Decision

- **Key pairs:** `KeyPairGenerator.getInstance("Ed25519")`. The private key is stored as its PKCS#8 encoding,
  encrypted. The public key is stored in OpenSSH form (`ssh-ed25519 <base64 blob>`, RFC 8709 wire encoding)
  with its fingerprint `SHA256:<unpadded base64>`, as `ssh-keygen -l` prints it. Folio encodes the wire format
  itself; there is no SSH library dependency.
- **Master-key ring:** `folio.crypto.master-keys` maps a positive integer version to a base64 secret of at
  least 32 bytes; `folio.crypto.active-key-version` names the version for new encryptions. Startup fails if
  the ring is empty, the active version is missing, or a secret is not base64 or too short. Messages name the
  version, never the secret.
- **Derivation:** `KDF.getInstance("HKDF-SHA256")` (Java 25), extract with the secret as input key material and
  the row's salt, expand with the info label `folio credential private key v1` (ASCII) to 32 bytes.
- **Encryption:** `AES/GCM/NoPadding` with a fresh 12-byte nonce from `SecureRandom` and a 128-bit tag.
- **Associated data:** 36 bytes, fixed-width big-endian fields with no separators: the credential UUID
  (16 bytes, most significant half first), the key UUID (16 bytes, the same way), the master-key version as a
  4-byte signed integer. Fixed widths make the encoding unambiguous.
- **Storage:** `credential_key` holds `algorithm` (`HKDF-SHA256/AES-256-GCM`), `master_key_version`, `salt`
  (32 bytes), `nonce` (12 bytes) and `ciphertext` (PKCS#8 plus the 16-byte tag). All five are null exactly when
  the key is `RETIRED`, enforced by a check constraint.
- **Key IDs** come from `select uuidv7()` before the insert, because the associated data includes them.
- **Plaintext handling:** byte arrays holding a private key or secret that Folio creates are zeroed after use.
  Classes holding secrets or ciphertext override `toString`. Nothing in the module logs.

## Consequences

- A ciphertext copied to another row, or relabelled with another version, fails authentication.
- The JDK's `SecretKeySpec`, `PKCS8EncodedKeySpec` and private-key objects keep internal copies that cannot be
  destroyed (`destroy()` throws). Those copies live until garbage collection; zeroing covers only Folio's own
  arrays.
- Changing the label or any encoding makes stored keys undecryptable. A new format needs a new `algorithm`
  value and a re-encryption.
- The decoded master secrets stay in memory for the life of the process.
