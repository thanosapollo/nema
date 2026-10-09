# Calls design

Status: approved v1 design. Calls are not implemented or shipped.
Code locations are in [omemo-calls-map.md](omemo-calls-map.md).

## Goal and scope

Goal: 1:1 audio calls with Conversations, Monocles, Cheogram and Dino, that
connect behind ordinary NATs and ring reliably on a locked phone.

Approved v1: audio only, 1:1, one call at a time, with the account on its
normal (non-Tor) route. Video follows in v2 on the same signalling. Group
calls are out of scope; there is no interoperable XMPP group-call protocol
to target.

## Protocols

| XEP | Namespace | Role |
|---|---|---|
| XEP-0166 Jingle 1.1.2 | `urn:xmpp:jingle:1` | Session IQs: initiate, accept, terminate, transport-info |
| XEP-0167 Jingle RTP 1.2.3 | `urn:xmpp:jingle:apps:rtp:1`, `…:rtp:audio`, `…:rtp:video` | Media description (Opus for audio) |
| XEP-0176 Jingle ICE-UDP 1.1.1 | `urn:xmpp:jingle:transports:ice-udp:1` | ICE candidates and credentials |
| XEP-0320 DTLS-SRTP 1.0.0 | `urn:xmpp:jingle:apps:dtls:0` | Fingerprint and setup role. Conversations rejects calls without it |
| XEP-0353 JMI 0.8.0 | `urn:xmpp:jingle-message:0` | Ringing via messages: propose, ringing, proceed, reject, retract, finish |
| XEP-0215 ExtDisco 1.0.0 | `urn:xmpp:extdisco:2` | Discover STUN/TURN and get short-lived TURN credentials |
| XEP-0115 Caps | | Announce and detect the features above |
| XEP-0293/0294/0338/0339 | rtcp-fb, hdrext, grouping, ssma | Map the extra lines that WebRTC's SDP carries |

Interop rules taken from Conversations' developer notes:

- Conversations shows the call button only if a contact resource announces
  `jingle:1`, `ice-udp:1`, `rtp:1`, `dtls:0` and `rtp:audio` (and
  optionally `rtp:video`) through caps, with mutual presence subscription.
- If any resource announces `jingle-message:0`, Conversations calls through
  JMI to the bare JID, otherwise with a direct `session-initiate`.
- The JMI `propose` must list the same media as the later
  `session-initiate`. A mismatch gets the call rejected.
- JMI 0.8.0 (February 2026) sends responses and `finish` to full JIDs.
  Nema follows that and accepts both forms on receive. JMI messages are
  `type='chat'` with a `<store/>` hint, so they reach Carbons and MAM. MAM
  catch-up must replay them to settle call state, and must never ring for a
  propose that an archived `retract`, `reject`, `proceed` or `finish`
  already settled.
- When accepting, Conversations also sends a legacy `<accept/>` to its own
  bare JID before `<proceed/>`, to stop its other devices ringing. Nema
  accepts that from mixed-client setups.
- Termination reasons carry human-readable `<text/>`. Nema shows it in the
  call log for debugging.

Nema's core is a translator between WebRTC SDP and Jingle elements, with
round-trip tests built from captured Conversations and Dino sessions. That
is where interop bugs live, so it needs fixture tests before any UI work.

## Android WebRTC library

Measured from Maven Central artefacts (October 2026), one `.so` per ABI:

| Artefact | Version / date | Licence | arm64-v8a | armeabi-v7a | x86_64 | Notes |
|---|---|---|---|---|---|---|
| `im.conversations.webrtc:webrtc-android` | 149.0.0, 2026-09 | Apache-2.0 / MIT | 9.0 MB | 4.9 MB | 10.8 MB | What Conversations 2.20.4 ships. Java API in `libs/libwebrtc.jar` (0.98 MB). **Needs compileSdk 37**: the AAR metadata says `minCompileSdk=37`, while Nema uses compileSdk 36 with AGP 8.13.2 |
| `ch.threema:webrtc-android` | 144.0.0, 2026-01 | Apache-2.0 / MIT | 8.9 MB | 4.8 MB | 10.7 MB | Same kind of build. Builds with Nema's current SDK |
| `io.getstream:stream-webrtc-android` | 1.3.10, 2025-09 | Apache-2.0 | 11.5 MB | 6.6 MB | 12.8 MB | Larger, adds Stream's Kotlin helpers. Older Chromium |

Spike (branch `spike/omemo-webrtc-size`): adding `ch.threema:webrtc-android`
144.0.0 grows the universal release APK from 14.37 MB to 48.29 MB (+33.9
MB), because all four ABIs are included and stored uncompressed. The 64-bit
libraries have 16 KB-aligned ELF load segments, which devices configured
with 16 KB pages (supported from Android 15) need. Zip alignment of the
uncompressed libraries is checked when the real build lands. The per-device
cost is about 9 MB on arm64.

**Approved media and packaging:**

- Use Conversations' `webrtc-android` build. It is the interop reference and
  tracks Chromium closely. It requires a compileSdk 37 / AGP bump, which is
  a separate toolchain seam. The selected dependency is
  `im.conversations.webrtc:webrtc-android`; no alternative WebRTC library
  fallback is approved.
- Ship per-ABI APKs (`splits.abi`, arm64-v8a and armeabi-v7a), so the
  self-update path does not download 34 MB of libraries that cannot load.
  x86/x86_64 only matter for emulators.

## Android integration

- **Telecom.** Use `androidx.core:core-telecom` (stable 1.0.1, minSdk 23,
  minCompileSdk 34; 1.1.0 is in beta) and its `CallsManager`, with
  self-managed calls. It wraps `ConnectionService` before API 34 and the
  platform call APIs after. This gives system call UI integration, Bluetooth
  and wearable routing, correct audio focus, and "busy on a phone call"
  semantics. Needs `MANAGE_OWN_CALLS`.
- **Foreground service.** The call runs in its own service with type
  `phoneCall` (`FOREGROUND_SERVICE_PHONE_CALL`; declaring `MANAGE_OWN_CALLS`
  meets that type's prerequisite) plus `microphone`
  (`FOREGROUND_SERVICE_MICROPHONE`, needs `RECORD_AUDIO`) while capturing.
  Video adds `camera`. `RECORD_AUDIO` and `CAMERA` are while-in-use
  permissions. On Android 14+ the microphone/camera service must start from
  a user-visible action: the call screen, or the user tapping Answer in the
  call notification. It must never start from a background stanza
  handler. The messaging service keeps its own type (map seam 8). Whether
  `remoteMessaging` is the right type for a persistent XMPP connection is a
  separate messaging-service question; Android describes that type as
  cross-device message continuity.
- **Notifications.** Every call has a `NotificationCompat.CallStyle`
  notification for its whole life: incoming (ringing, with a full-screen
  intent), outgoing (dialling), and ongoing (with hang-up). Core-telecom's
  `CallsManager.addCall` expects a valid CallStyle notification soon after
  the call is added (within seconds) to keep foreground priority, so the
  notification is posted at registration and updated in place on each state
  change. Incoming calls use a dedicated high-importance channel.
- **Full-screen intent.** On Android 14+, whether `USE_FULL_SCREEN_INTENT`
  is granted depends on the installer and its policy: Play grants it only to
  apps whose core function it approves for calling or alarms, and users can
  revoke it. Nema checks `NotificationManager.canUseFullScreenIntent()` on
  each incoming call and, when it is not granted, falls back to a heads-up
  notification plus a settings hint. Nema is distributed outside Play;
  eligibility would need a declaration if it ever ships there.
- **Audio.** Core-telecom owns audio focus and routing. Nema does not
  request focus or switch Bluetooth/SCO itself, and WebRTC's audio device
  module is configured to leave audio mode and focus to the platform call.
  Route between earpiece, speaker, wired and Bluetooth through core-telecom's
  `CallEndpoint` API. Enable the proximity-sensor screen lock in earpiece
  mode. Core-telecom strongly recommends `BLUETOOTH_CONNECT` on API 31+ for
  full Bluetooth endpoint information. Without it, platform-managed Bluetooth
  audio still works, so a denial must not disable it.
- **Permissions to add:** `RECORD_AUDIO` (asked at the first call, not at
  install), `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_PHONE_CALL`,
  `FOREGROUND_SERVICE_MICROPHONE`, `USE_FULL_SCREEN_INTENT`, and
  `BLUETOOTH_CONNECT` (recommended). Video later adds `CAMERA` and
  `FOREGROUND_SERVICE_CAMERA`.
- **Reachability.** An incoming call can only ring if the XMPP session is
  alive when the JMI `propose` arrives. A foreground service does not exempt
  the app from Doze network suspension. Reliable ringing on an idle phone
  needs a battery-optimisation exemption (as Conversations asks for) or
  push wake-up, and must be tested with forced Doze
  (`adb shell dumpsys deviceidle force-idle`), not just a locked screen.
  Existing messaging reachability needs separate reliability verification.
- **Tor.** Accounts routed through Tor (`OnionXmppConnection`) must not
  announce or place calls, because ICE candidates expose the device's
  addresses. Conversations does the same. This is a capability rule (map
  seam 6), not a UI hide.

## Server requirements

Calls require working XEP-0215 discovery and a reachable TURN relay.
Discovery alone does not prove relaying works. For an ejabberd deployment,
the following are configuration requirements, not a statement about any
live server or an instruction to change one:

1. **Set `turn_ipv4_address`** on the STUN/TURN listener to the server's public
   IPv4. ejabberd's documentation says it has no default and should be set
   explicitly. Without it, the relay address handed to clients may be wrong.
2. **Open the TURN relay port range.** ejabberd allocates relays from
   `turn_min_port`..`turn_max_port` (default 49152-65535), and the host
   firewall must accept inbound UDP there. Set a narrow range (for example
   49152-49251) on the listener and allow exactly that range.
3. **Set an explicit `mod_stun_disco` `secret`.** Credentials then survive
   restarts. The value belongs in the private config, not in any repository.
4. **Optional:** a TURN over TLS (`turns`, TCP) listener for restrictive
   networks. ejabberd's automatic announcement (`offer_local_services`)
   skips TLS listeners, so this also needs an explicit `mod_stun_disco`
   `services` entry (type `turns`, transport `tcp`, port, and a hostname
   matching the certificate). Conversations recommends `turns` on port 443,
   which conflicts with any HTTPS service on the same IP. The STUN/TURN port
   number itself does not matter to clients, because ExtDisco announces it.
5. **Verify after the change** from the client side: an XEP-0215 query
   (`<services xmlns='urn:xmpp:extdisco:2'/>`) from a test account returns
   `stun` and `turn` entries with credentials. Then a TURN allocation from
   another network succeeds, for example with a WebRTC trickle-ICE page
   using the issued credentials (look for a `relay` candidate). The
   Conversations compliance tester checks discovery only.

## Call security

- **DTLS-SRTP** encrypts media end to end (XEP-0320). The certificate
  fingerprint travels in Jingle IQs, which are protected only by c2s/s2s
  TLS. A malicious server could swap fingerprints and sit in the middle.
- **OMEMO-verified calls** close that gap. Conversations implements a
  vendor protocol (`http://gultsch.de/xmpp/drafts/omemo/dlts-srtp-verification`).
  The callee puts its OMEMO device ID in the JMI `proceed`. The caller's
  fingerprints are then OMEMO 0.3 payloads encrypted to that callee device.
  The callee's answers are encrypted to the caller device named as sender
  (`sid`) of the first encrypted offer. Both sides pin that device pair and
  its OMEMO identity key for the whole negotiation; the same device ID with
  a different identity is a failure. The protocol is optional at the start:
  a callee that sends no device ID, or a caller that ignores it, uses plain
  XEP-0320. A caller that sent an encrypted offer requires an encrypted
  answer. From then on, a plaintext fingerprint in the same session is a
  downgrade and terminates the call (as Conversations does).
- **Shield semantics.** Nema shows "verified call" only when the peer device
  is a manually verified OMEMO identity. A blind-trusted (BTBV) device gives
  "encrypted call, not verified", because blind trust is not verification.
  Nema adds this after legacy OMEMO ships (see [omemo.md](omemo.md)).
- **IP exposure.** ICE reveals local and public addresses to the peer. A
  per-account "relay only" option (only TURN candidates) hides them, at
  some latency cost. Off by default, as in Conversations.
- **Credentials.** TURN credentials from ExtDisco are short-lived and
  stored only in memory. They never go to logs.
- **Call log.** Nema stores call events (direction, peer, duration,
  outcome) as timeline rows. This needs a schema addition in the call slice,
  not now.

## Interop test plan

Use dedicated test accounts on one server, plus an account on another
server. Clients:
Conversations, Monocles, Dino. Networks: same Wi-Fi, Wi-Fi to mobile data,
and mobile data to mobile data. Mobile-to-mobile does not force TURN by
itself. TURN is proven with the relay-only option, checking that WebRTC's
stats show a `relay` candidate in the selected pair, not just that a relay
candidate was gathered.

1. Nema calls each client and each client calls Nema: ring, accept, 2
   minutes of two-way audio, hang up from each side.
2. Reject, retract (caller cancels while ringing), busy (callee already in a
   call) and no answer (timeout) all end in the correct state on both sides.
3. Multi-resource: the callee has two online clients. Accepting on one stops
   ringing on the other (JMI `proceed` and `finish` to full JIDs,
   Conversations' `<accept/>`, carbons). Restart a client mid-ring; MAM
   replay does not ring for a settled call.
4. ICE: confirm the `host`, `srflx` and `relay` paths, each by its
   selected candidate pair. Interrupt the network mid-call; the ICE restart
   or a clean terminate is visible.
5. Locked phone, then forced Doze: an incoming call shows full screen.
   With the permission revoked, the heads-up fallback appears.
6. Audio routing: earpiece, speaker, wired headset and Bluetooth headset
   switch without dropping the call.
7. Tor account: no call button, and caps exclude the Jingle features.
8. Verified calls, after OMEMO: against Conversations, a verified device
   shows the shield, a BTBV device shows "encrypted, not verified", an
   untrusted one shows neither. A plaintext answer to an encrypted offer, a
   later plaintext fingerprint, and a changed identity behind the same
   device ID each terminate the call.

## First slices

1. Map seams 5 to 8 (IQ, PEP, capabilities, FGS split), plus the compileSdk
   37 / AGP toolchain seam and per-ABI APK packaging.
2. ExtDisco client plus the server changes above, proven with a relay
   candidate.
3. SDP to Jingle translator, tested only with captured fixtures from
   Conversations and Dino.
4. Outgoing audio call: JMI, Jingle, WebRTC, core-telecom, minimal call
   screen.
5. Incoming audio call with full-screen ringing.
6. Video. Then OMEMO-verified calls once OMEMO ships.
