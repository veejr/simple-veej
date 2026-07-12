# veejr for Android

Native Android client for [veejr](https://github.com/veejr/veejr-server), a
self-hostable Phoenix application for end-to-end encrypted messages,
attachments, locations, and map notes.

> **Status:** initial architecture scaffold. Encryption and server integration
> are not implemented yet.

## Architecture

The Phoenix server remains authoritative for authentication, authorization,
ciphertext storage, consent, federation, and delivery. Encryption and
decryption happen exclusively on the Android device.

The canonical contract is the
[veejr client protocol v1](https://github.com/veejr/veejr-server/blob/main/docs/CLIENT_PROTOCOL_V1.md).

## Build

Prerequisites:

- JDK 17
- Android SDK 35

```sh
./gradlew lint test assembleDebug
```

The initial project uses Kotlin, Jetpack Compose, and a small multi-module
boundary around models, networking, and cryptography.

## Security

Review [docs/SECURITY.md](docs/SECURITY.md) before implementing features that
handle keys, plaintext, tokens, or decrypted files.
