# simple-veej security model

simple-veej is an early-stage encrypted video-calling client. Protocol fixture
tests establish compatibility; they do not replace independent security review.

## Secrets and storage

Setup reads the encryption passphrase locally, derives the protocol-v1 wrapping
key, and verifies that the unwrapped secret derives the account's public key.
It clears the mutable passphrase and temporary key arrays after use. JVM and
UI string copies cannot be guaranteed to be erased.

Unlike the original messaging app's memory-only unlock policy, simple-veej
intentionally remembers the identity across app and device restarts. Calls do
not ask for the passphrase. `SimpleStore` encrypts the identity and token records
with AES-256-GCM and a non-exportable Android Keystore key. The passphrase itself
is never persisted. This policy has no timed expiry and is independent of the
web client's browser unlock-duration preference.

The Keystore key does not require biometric or device authentication for each
use. Anyone who can use the configured app can place and answer calls. Hardware
backing depends on the device; a non-exportable key alone does not protect
against a compromised app process or an attacker controlling an unlocked phone.
Android backup is disabled in the manifest.

Endpoint, account ID, friend metadata/public key, button label and the last
push-registration time are ordinary preferences. Starting over clears all app
preferences after attempting logout; the Keystore wrapping key remains.
Undecryptable records return null and require recovery/setup. Restored identity
bytes are held temporarily for call signaling and zeroed when the sealer is
destroyed. The app does not record calls or persist decrypted messages/files.

## Network and peer trust

Release builds require HTTPS and WSS. Only debug builds allow cleartext servers
for local development. REST and socket clients do not follow redirects: use the
actual instance hostname, not the `veejr.com` redirect. Credentials must not be
forwarded to another origin. Socket upgrade URLs contain an access token and
must never be logged in full.

REST access tokens are refreshed after a 401. Refresh rotation is serialized;
a rejected refresh removes the token record. Temporary transport failures keep
credentials and allow retries. Socket 401/403 upgrade failures trigger account
validation/refresh. Connection loss fails waiting actions without cancelling
their caller's coroutine.

`SignalSealer` uses the shared TweetNaCl implementation for X25519 and
XSalsa20-Poly1305 boxes. Invalid or malformed signals are ignored. WebRTC uses
transport encryption for audio/video, including when TURN relays packets.
The server still sees account relationships, call lifecycle and routing metadata.
Peer public keys come from server-provided account/call data; this app does not
provide an independent fingerprint-verification UI. Do not treat it as protection
against malicious substitution of those public keys by a trusted server.

FCM receives call identifiers and caller/expiry metadata, plus the sender handle
and kind of new messages (never message text), not SDP, identity
secrets, passphrases, or media. The receiving phone checks expiry, then uses the
authenticated server channel to answer. Message text is decrypted on the phone only and shown in the app, never in a
notification. Notification text can expose caller or sender
information on the lock screen according to the phone's settings.

## Diagnostics and tests

Do not send or log passphrases, raw identity keys, access/refresh tokens, complete
socket URLs, or decrypted signaling/media. Release ProGuard rules strip debug
and verbose Android logging; warning/error logging remains. Debug WebRTC logs
can include network candidates and call identifiers, so redact them before
sharing and collect only the relevant interval.

Canonical `protocol-fixtures/` tests cover browser-compatible wrapping, boxes,
and attachment primitives inherited by the shared crypto module. The calling
app has no attachment viewer or FileProvider. Socket lifecycle tests cover
recovery and cleanup but do not exercise Android Keystore or native WebRTC.
Test these on a real device as described in [CONNECTIONS.md](CONNECTIONS.md).
