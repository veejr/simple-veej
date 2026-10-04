# simple-veej architecture

simple-veej is a native Kotlin/Jetpack Compose Android video phone, forked from
veejr-android. It calls one selected friend with a large home-screen button.
It has no inbox, attachment viewer, message composer, or Room database.

## Modules and ownership

- `app`: setup and Compose screens, Android Keystore storage, push and call
  notifications, Phoenix signaling, and WebRTC media.
- `core:model`: protocol types shared with veejr-android.
- `core:network`: API v1 DTOs and OkHttp transport, plus `AuthSessionManager`.
- `core:crypto`: framework-independent TweetNaCl cryptography and fixtures.

Dependency direction is `app -> core:network/core:crypto -> core:model`.
Some shared APIs support messaging; their presence does not mean this app has
messaging features. Core changes should also be considered for veejr-android.

## Setup and device storage

`SetupModel` signs in to the selected instance, unwraps the existing account
identity using the passphrase, verifies its public key, and lets the helper
choose a friend and button label. An account without keys must first set them
up using veejr. Settings is opened by long-pressing the home-screen button;
Change person returns to the friend picker without another unlock.

`SimpleStore` implements `SessionTokenStore`. It stores the identity secret
and session tokens as separate AES-256-GCM records in SharedPreferences. The
wrapping key is non-exportable and belongs to Android Keystore. The endpoint,
user ID, selected person's metadata, and push-registration timestamp are stored
as ordinary preferences. The passphrase is not stored.

An already configured app restores its setup and starts calling services on
process startup. It does not revalidate the account or ask for a passphrase on
every launch. `AuthSessionManager` refreshes tokens after a REST 401, serializes
refresh rotation, and clears tokens when refresh is rejected. The calling UI
returns to setup when the token store is empty. A temporary network failure
must not be interpreted as sign-out.

## Signaling and recovery

`CallController` owns one `PhoenixSocket` on `calls:v1` at
`/api/v1/socket/websocket`. The access token is sent in the WSS upgrade URL.
The server must support the `calls: 1` extension. The channel handles starting,
accepting, declining and ending calls, and relays sealed signaling frames.

`PhoenixSocket` uses Phoenix Channels V2 array frames. Every connection joins
again, with a ten-second join deadline. Disconnects and failed sends complete
pending pushes with null, allowing the controller to show an error rather than
leaving a cancelled call action stuck. Pushes also have their own deadlines.
The reconnect delay grows from one second after an unsuccessful attempt to a
15-second cap; a previously joined connection retries after 500 milliseconds.
HTTP 401/403 upgrade failures request account/token refresh before retrying.
Transient URL/token lookup errors are retried without discarding credentials.

A Phoenix heartbeat is sent every 30 seconds; an unacknowledged heartbeat causes
reconnection at the next interval. OkHttp also sends transport pings every
20 seconds. Timers belong to the connection lifecycle, stopped sockets are
cancelled, and callbacks from older connections cannot change current state.
OkHttp callbacks are marshalled onto the controller's coroutine scope.

After a rejoin, an outgoing or active call is reaccepted so server presence and
signaling move to the new channel. The server's reconnect grace still applies:
a sufficiently long outage ends the call. Reconnection is not a guarantee that
an arbitrarily interrupted call can resume.

## Media

`CallEngine` captures camera/microphone media and maintains one WebRTC peer
connection. SDP offers/answers, ICE candidates and media-state messages are
sealed with the account identity and the peer key using `SignalSealer`.
The server supplies peer metadata and ICE servers; WebRTC media flows directly
or through TURN using its transport encryption.

Negotiation runs under a mutex on a dedicated executor. The polite participant
yields on an offer collision. A disconnected connection waits five seconds
before requesting an ICE restart; failed connections are rebuilt, with bounded
retry counts. Generation checks discard callbacks from replaced connections.
Ending a call disposes media resources and destroys the signal sealer's secret.

## Incoming calls and Android lifecycle

`SimpleVeejApp` owns the application coroutine scope and call controller.
`CallService` supplies the ongoing camera/microphone foreground notification.
`PushService` receives FCM call-ring/cancellation metadata and hands it to the
controller on the main thread. Push is a wake-up hint; answering still requires
an authenticated socket and server acceptance.

FCM requires this app's `google-services.json` from the server's Firebase
project. Without it, the app can ring while its socket is running, but cannot
reliably wake when closed. Notification permission, the call notification
channel, Android full-screen intent access, and device battery policies also
matter. A force-stopped app must be reopened. Expired rings are ignored and
unanswered notifications expire locally.

## Verification

Run `./gradlew lint test assembleDebug` (or `gradlew.bat` on Windows).
`PhoenixSocketTest` covers disconnect/rejoin, join and push deadlines,
credential lookup failure, 401/403 refresh, heartbeat acknowledgement, failed
sends, channel errors, and stop/restart cleanup using virtual time and a fake
transport. `ProtocolTest` covers signal shapes, sealing and role selection;
`core:*` tests cover crypto fixtures, endpoint validation and REST sessions.

These JVM tests do not validate real camera/audio, Android Keystore, FCM wake-up,
NAT traversal, or browser/phone WebRTC interoperability. Use the device checklist
in [CONNECTIONS.md](CONNECTIONS.md) before distributing a release.

The wire authority remains
[CLIENT_PROTOCOL_V1.md](https://github.com/veejr/veejr-server/blob/main/docs/CLIENT_PROTOCOL_V1.md),
particularly section 26 for calls.
