# Security model

veejr is an early-stage end-to-end encrypted application. Do not rely on this
client for sensitive data until its protocol implementation and cryptographic
interoperability have been independently reviewed.

The Android client must never send or log:

- the encryption passphrase;
- the raw X25519 secret key;
- decrypted message or attachment content;
- attachment secretbox keys;
- access or refresh tokens; or
- complete capability URLs.

Release builds reject cleartext instance URLs. The portable identity key stays
wrapped using the protocol-defined passphrase format. A future device-local
copy may be additionally protected by Android Keystore but must not replace the
portable representation.

Session tokens are persisted as a single AES-256-GCM record. The encryption key
is generated inside Android Keystore and is non-exportable; malformed or
undecryptable records are discarded. The instance URL is non-secret metadata
and is stored separately. Selecting a different instance clears the prior token
record so credentials cannot cross server boundaries.

## Cryptographic implementation

Protocol-v1 boxes use `org.purejava:tweetnacl-java`. The dependency is a
pure-Java port of TweetNaCl, so the same implementation runs in JVM tests and
on Android without native ABI packaging. Canonical fixtures assert
byte-for-byte compatibility with the browser for PBKDF2 wrapping, public-key
boxes, and attachment secretboxes, including authentication failure after
ciphertext tampering.

Passing interoperability tests does not replace an independent security
review. Dependency updates and changes under `core:crypto` require fixture
validation and focused review.

The network client disables HTTP and HTTPS redirects. This is deliberate:
native API credentials must never follow a server response to another origin.
Release code permits only HTTPS instance URLs, does not install an HTTP logging
interceptor, and keeps access and refresh tokens out of exception messages.
Concurrent requests share a single refresh-token rotation. A rejected refresh
clears the local session, and logout clears local tokens even when the server
cannot be reached. The production `SessionTokenStore` must encrypt persisted
tokens with an Android Keystore-protected key.
