# Android architecture

The veejr Android app is a native client for an existing veejr Phoenix
instance. Phoenix remains authoritative for accounts, authorization,
ciphertext storage, consent, federation, and delivery. The Android client owns
passphrase handling, private keys, encryption, decryption, and plaintext.

## Initial modules

- `app`: Compose application shell and navigation host.
- `core:model`: protocol types shared across features.
- `core:network`: `/api/v1` transport boundary and DTO mapping.
- `core:crypto`: client-only cryptography and Android Keystore integration.

Feature modules, Room persistence, synchronization, and dependency injection
will be added as the first encrypted-messaging vertical slice is implemented.

## Dependency direction

```text
app -> core:network -> core:model
app -> core:crypto  -> core:model
```

Network code must not depend on crypto implementation types that contain raw
secrets. Decrypted content is memory-only by default and is not persisted in
Room, saved UI state, analytics, logs, or crash reports.

## Protocol authority

The canonical client protocol is maintained in
[`veejr-server/docs/CLIENT_PROTOCOL_V1.md`](https://github.com/veejr/veejr-server/blob/main/docs/CLIENT_PROTOCOL_V1.md).
This repository will contain machine-readable copies of published test vectors
under `protocol-fixtures/`.
