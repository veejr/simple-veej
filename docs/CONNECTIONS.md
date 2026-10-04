# Connection troubleshooting and device checks

## Identify the failing stage

1. **Cannot sign in:** open the configured instance in the phone's browser.
   Use `https://veejr.dyndns-server.com`, not `https://veejr.com`; the latter
   redirects and native authenticated clients intentionally refuse redirects.
   Check the actual hostname, HTTPS certificate, network and credentials.
2. **Calling never reaches the other person:** verify the server advertises
   `extensions.calls = 1` at `/api/v1/capabilities`. The authenticated websocket
   at `/api/v1/socket/websocket` must reach Phoenix through the reverse proxy.
   Retry once connectivity returns; do not reset the phone merely for an outage.
3. **Rings but no media:** check camera/microphone permissions and test both on
   Wi-Fi and mobile data. Working HTTPS does not prove STUN/TURN reachability.
   The server-provided ICE servers and UDP/TCP relay ports must be accessible.
4. **Only rings while open:** long-press the call button and inspect push status.
   Verify the build includes Firebase configuration for `org.veejr.simpleveej`
   in the same Firebase project as the server. Check notification permissions,
   channel settings, full-screen permission and device battery restrictions.
   Reopen the app after force-stop; a force-stopped app cannot be relied on to ring.
5. **Signed out:** a revoked or rejected refresh requires setup again. Temporary
   DNS, Wi-Fi or server failures should instead retry while keeping credentials.

If public HTTPS fails but direct requests inside the reverse proxy succeed,
inspect the host's published port and port forwarding as well as Caddy. This
can be an infrastructure problem, independent of the Android client. Server
operators should use veejr-server's operations runbook and verify the public
route after any proxy repair.

## Automated checks

Run `./gradlew lint test assembleDebug`. Socket regression tests use fake
WebSockets and coroutine virtual time, so they need no live account or server.
They cover pending-request failure, retry, stalled joins, expired authentication,
missed heartbeats and lifecycle cleanup. Tests never use real access tokens.

## Real-device release checklist

Use two test accounts with configured keys and an accepted friendship. Record
app/server versions, Android version, network types, failing stage and approximate
time. Never put credentials or unredacted socket URLs into a bug report.

- Complete setup, choose the other account, restart the app and call without
  another passphrase prompt. Change the chosen person and cancel that change.
- Place and answer calls in both directions between phone and browser; confirm
  both audio and video, mute, decline, hang-up and missed-call expiry.
- Repeat with Wi-Fi/mobile data and, where available, a TURN-only network.
- Briefly interrupt Wi-Fi during dialing and during an active call. Confirm the
  UI either recovers or returns a useful failure instead of staying stuck.
  A long outage may exceed the server's reconnect grace and end the call.
- Background/lock the phone, then ring it. Repeat with notifications or
  full-screen access disabled and verify the expected fallback.
- Test revoked sessions separately from temporary offline state: revocation
  requires setup; going offline must not delete the configured identity.
- Start over and configure again; check that old socket callbacks and calls do
  not affect the new session.

An APK assembly or JVM test pass alone does not establish these device results.
