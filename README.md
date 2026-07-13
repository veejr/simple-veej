# veejr for Android

Native Android client for [veejr](https://github.com/veejr/veejr-server), a
self-hostable Phoenix application for end-to-end encrypted messages,
attachments, locations, and map notes.

> **Status:** early native client with instance selection, authentication,
> encrypted session persistence, portable identity-key setup/unlock, foreground
> sync, message consent, encrypted conversations, attachment viewing, and
> history browsing.

## Current experience

The Compose interface follows the server application's information model while
using mobile-first navigation:

- **Messages** is a WhatsApp-style conversation list combining contacts and
  groups. It shows the latest decrypted item, handles pending consent requests,
  and opens an encrypted conversation with its own composer.
- **Attachments** appear inside message bubbles. They are fetched as opaque
  encrypted blobs only when requested, authenticated and decrypted locally,
  previewed inline for images, and opened through Android's installed viewer
  for PDF, audio, and other supported file types. The composer can pick up to
  ten files or launch the device's audio recorder; every result is encrypted
  locally before upload.
- **History** opens from Account as a dedicated chronological encrypted feed.
  It can be filtered by Everything, Messages, Locations, or Notes and loads the
  next 50 envelopes as the reader approaches the end.
- **Contacts** and **Groups** open conversations when tapped and expose
  expandable delivery-policy and private-note settings.
- **Account** shows the active instance and unlocked identity state and provides
  history, sign-out, and instance-reset actions.

Android currently sends text, file attachments, and recorded audio and reads
protocol-v1 message, location, note, and attachment payloads. Maps,
contact-management, and group-editing flows remain on the parity roadmap.

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

For an emulator or physical Android device running the `debug` variant, connect
the device with ADB and verify that it appears:

```sh
adb devices -l
```

Then reverse the development port before launching the app:

```sh
adb reverse tcp:4000 tcp:4000
```

The debug app defaults to `http://127.0.0.1:4000`. HTTP is accepted only by
debug builds; release builds require HTTPS and disable Android cleartext
traffic.

Verify the active mapping with:

```sh
adb reverse --list
```

The output should contain `tcp:4000 tcp:4000`. The mapping belongs to the
connected device and can disappear when the device disconnects, wireless
debugging reconnects, or ADB restarts. Run the reverse command again whenever
the Android app reports that `http://127.0.0.1:4000` cannot be reached.

If more than one device is connected, target the intended device explicitly:

```sh
adb -s DEVICE_SERIAL reverse tcp:4000 tcp:4000
```

Also confirm that Phoenix is still listening on port 4000 on the development
machine. `127.0.0.1` without ADB reversal refers only to the Android device
itself; it does not reach the development machine directly.

## Security

Review [docs/SECURITY.md](docs/SECURITY.md) before implementing features that
handle keys, plaintext, tokens, or decrypted files.
