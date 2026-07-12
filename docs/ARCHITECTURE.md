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
The application shell currently owns the instance-selection and authentication
flow while those boundaries remain small.

## Dependency direction

```text
app -> core:network -> core:model
app -> core:crypto  -> core:model
```

Network code must not depend on crypto implementation types that contain raw
secrets. Decrypted content is memory-only by default and is not persisted in
Room, saved UI state, analytics, logs, or crash reports.

`core:crypto` uses the pure-Java TweetNaCl port for protocol-v1 X25519,
XSalsa20, and Poly1305 compatibility. Keeping this module independent of the
Android framework makes every canonical vector executable as a fast JVM test.
Android Keystore integration will wrap device-local material above this layer;
it does not replace the portable protocol representation.

`core:network` uses suspendable OkHttp calls and Kotlin serialization for the
versioned JSON contract. Its client does not follow redirects, preventing an
authenticated request from silently crossing instance origins. Access and
refresh tokens are added only to the endpoints that require them, and
token-bearing objects redact their string representation.

`AuthSessionManager` is the session boundary above that transport. It persists
successful login tokens through `SessionTokenStore`, serializes refresh-token
rotation, retries an authenticated operation once after a 401, and always
clears local state during logout. The token-store interface deliberately has no
storage implementation yet; an Android Keystore-backed adapter belongs at the
application boundary rather than in the framework-independent network module.

The application starts by restoring the selected instance and encrypted token
record. A valid session is verified through `/api/v1/me`; otherwise the app
lands on sign-in with an actionable error. A new instance must advertise API
v1 capabilities before the app stores it or presents the credential form.

After authentication, accounts without identity keys enter setup; configured
accounts enter unlock. PBKDF2 and NaCl work runs off the UI thread. The raw
X25519 secret exists only in ViewModel-owned memory for the active process and
is zeroed on logout, instance change, failed setup, and ViewModel teardown.

The first inbox slice loads consent metadata only. Accepting a notification
releases its encrypted envelope, which Android authenticates and decrypts in
memory using the unlocked identity; declining removes the pending item without
fetching content. Plaintext messages remain in process memory only.

The text composer resolves an accepted friend together with the sender's
self-copy, serializes one protocol-v1 payload, and seals it independently to
each public key. A fresh 128-bit idempotency key accompanies every batch so a
network retry cannot create duplicate messages.

## Protocol authority

The canonical client protocol is maintained in
[`veejr-server/docs/CLIENT_PROTOCOL_V1.md`](https://github.com/veejr/veejr-server/blob/main/docs/CLIENT_PROTOCOL_V1.md).
This repository will contain machine-readable copies of published test vectors
under `protocol-fixtures/`.
