# simple-veej

A one-button Android video phone for veejr. The home screen is a single
green button, "Call Mom", that rings one chosen veejr friend. Incoming calls
show two buttons, Answer and Decline, and a call shows Mute and Hang up.

It is built for someone who should never see a menu. Setup happens once,
usually by whoever hands over the phone.

## How it works

- **Setup (once):** sign in, enter the encryption passphrase once, and pick
  the person and the name shown on the button. The identity key is kept on
  the device, encrypted by a non-exportable Android Keystore key (the
  device-local copy allowed by client protocol v1 §8). Calls never ask for the
  passphrase.
- **Calling:** the app speaks the `calls: 1` extension of client protocol v1
  (`veejr-server/docs/CLIENT_PROTOCOL_V1.md` §26). A Phoenix channel at
  `/api/v1/socket` drives the existing server call lifecycle. Every SDP/ICE
  signal is sealed with `nacl.box` to the peer's pinned key, and media goes
  peer to peer over WebRTC. A simple-veej phone and a veejr browser tab can
  call each other.
- **Ringing while closed:** the server sends a content-free, high-priority FCM
  data message. The app shows a full-screen call notification and answers
  over the socket.
- **Settings:** long-press the big button.

## Build

Use the checked-in Gradle wrapper and Android SDK 35. The daemon configuration
requests JetBrains JDK 21; the Kotlin/Java toolchain targets JDK 17. Gradle can
provision these toolchains, so the first build needs network access.
On Apple Silicon without Rosetta,
point Gradle at Android Studio's bundled arm64 runtime:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew lint test assembleDebug
```

The debug build defaults to `http://127.0.0.1:4000`; run
`adb reverse tcp:4000 tcp:4000` so the phone reaches Phoenix on the development machine.
Release builds default to the production instance and require HTTPS.
Use the actual instance URL (`https://veejr.dyndns-server.com`), not the
`https://veejr.com` redirect: the native client intentionally refuses redirects.

### Push (optional)

To ring a phone while the app is closed, register an Android app with the
package `org.veejr.simpleveej` in the **same** Firebase project as the
server's `FCM_SERVICE_ACCOUNT_JSON`. Put its `google-services.json` in `app/`;
it is git-ignored. Without it, the app rings only while it is
running.

## Server requirement

The instance must run a veejr server with the native calls extension
(`GET /api/v1/capabilities` includes `"extensions": {"calls": 1}`).

## Relationship to veejr-android

This repository is a fork of `veejr/veejr-android`. It keeps the shared
`core:*` modules (protocol models, networking, and cryptography) and replaces
the full messaging app with the simple-veej calling app in `app/`.

Keep the core in step with the messaging app by merging from upstream:

```sh
git remote add upstream https://github.com/veejr/veejr-android.git
git fetch upstream
git merge upstream/main   # resolve: keep this repo's app/, take upstream core/
```

Fixes to `core/` that are made here should be sent upstream as well.

## Documentation and connection checks

- [Architecture](docs/ARCHITECTURE.md): setup, encrypted device storage,
  signaling recovery, media, and incoming calls.
- [Security model](docs/SECURITY.md): remembered identity, peer trust and logging.
- [Connection troubleshooting](docs/CONNECTIONS.md): distinguish sign-in,
  signaling, media and background-ring failures, plus a real-device checklist.

JVM socket tests simulate outages, stalled joins, expired authentication and
stop/restart cleanup. They do not replace phone-to-browser call tests on Wi-Fi
and mobile data or checks of Firebase and Android notification permissions.
