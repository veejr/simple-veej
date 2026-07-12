# veejr for Android

Native Android client for [veejr](https://github.com/veejr/veejr-server), a
self-hostable Phoenix application for end-to-end encrypted messages,
attachments, locations, and map notes.

> **Status:** early native client with instance selection, authentication,
> encrypted session persistence, and identity-key setup/unlock.

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

## Local development

Start Phoenix on the host:

```sh
cd /path/to/veejr-server
mix phx.server
```

Run the Android `debug` variant in an emulator, then enter
`http://10.0.2.2:4000` as the instance URL. `10.0.2.2` is the emulator's bridge
to the host loopback interface. HTTP is accepted only by debug builds; release
builds require HTTPS and disable Android cleartext traffic.

## Security

Review [docs/SECURITY.md](docs/SECURITY.md) before implementing features that
handle keys, plaintext, tokens, or decrypted files.
