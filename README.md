# veejr for Android

Native Android client for [veejr](https://github.com/veejr/veejr-server), a
self-hostable Phoenix application for end-to-end encrypted messages,
attachments, locations, and map notes.

> **Status:** early native client with instance selection, authentication,
> encrypted session persistence, portable identity-key setup/unlock, foreground
> sync, message consent, encrypted conversations, and history browsing.

## Current experience

The Compose interface follows the server application's information model while
using mobile-first navigation:

- **Messages** is a WhatsApp-style conversation list combining contacts and
  groups. It shows the latest decrypted item, handles pending consent requests,
  and opens an encrypted conversation with its own composer.
- **History** is the chronological encrypted feed. It can be filtered by
  Everything, Messages, Locations, or Notes.
- **Contacts** and **Groups** open conversations when tapped and expose
  expandable delivery-policy and private-note settings.
- **Account** shows the active instance and unlocked identity state and provides
  sign-out and instance-reset actions.

Android currently sends text messages and reads protocol-v1 message, location,
and note payloads. Native attachment, map, contact-management, and group-editing
flows remain on the parity roadmap.

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
./gradlew testDebugUnitTest lintDebug assembleDebug
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

For a physical Android device connected with ADB, reverse the development port
and use `http://127.0.0.1:4000` in the app:

```sh
adb reverse tcp:4000 tcp:4000
```

The reverse mapping lasts only while the device remains connected. Confirm that
Phoenix is running and check the mapping with `adb reverse --list` if the app
cannot refresh.

## Security

Review [docs/SECURITY.md](docs/SECURITY.md) before implementing features that
handle keys, plaintext, tokens, or decrypted files.
