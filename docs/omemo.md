# OMEMO design

Status: approved v1 design. OMEMO is not implemented or shipped.
Today's behaviour is in [protected-content.md](protected-content.md), and
code locations are in [omemo-calls-map.md](omemo-calls-map.md).

## Goal and non-goals

Goal: 1:1 chats that read and send end-to-end encrypted messages with the
clients people use today (Conversations, Monocles, Cheogram, Dino, Gajim).
The same session state must work across live delivery, carbons, offline
delivery and MAM catch-up, and the UI must say plainly what it could and
could not decrypt.

Not in v1: group chats, encrypted calls signalling, cross-device history
transfer, key backup.

## Protocol target

There are two incompatible OMEMO generations.

| | Legacy OMEMO ("0.3") | OMEMO 2 |
|---|---|---|
| Namespace | `eu.siacs.conversations.axolotl` | `urn:xmpp:omemo:2` |
| Spec | XEP-0384 0.3.0, as deployed | XEP-0384 0.8+ (current 0.9.1, 2026-04-06, Experimental) |
| Encrypts | `<body>` text only | Any stanza content, via SCE (XEP-0420) |
| Payload | AES-128-GCM. The 16-byte key and the 16-byte tag are sealed per device in a Signal message | AES-256-CBC plus truncated HMAC-SHA-256, keys from HKDF |
| Key agreement | Signal X3DH and Double Ratchet, Signal protobuf framing | X3DH and Double Ratchet with OMEMO's own framing, Ed25519 identities |
| PEP | `…axolotl.devicelist`, one `…axolotl.bundles:<id>` node per device | `urn:xmpp:omemo:2:devices`, one `urn:xmpp:omemo:2:bundles` node |
| Deployed in | Conversations and forks, Dino, Gajim, Profanity, Movim, Siskin | Some newer clients. The clients above do not use it in interop |

Evidence: Conversations 2.20.4 still depends on
`org.whispersystems:signal-protocol-java:2.6.2` (its `build.gradle`). Dino's
source references only `eu.siacs.conversations.axolotl`. Smack 4.5.0
(September 2026) still ships only the `VAxolotl` element set.

**Approved protocol:** legacy OMEMO only in v1. Keep the existing `MODERN` parse
path so OMEMO 2 messages show an honest "not supported yet" state. Revisit
OMEMO 2 when one of Conversations or Dino ships it. Both protocols are
end-to-end encrypted. Legacy OMEMO's known gap is metadata: reply, correction,
reaction and chat-state elements stay in plaintext next to the encrypted
body.

## Library options

| Option | Licence | Status | Fit |
|---|---|---|---|
| A. `smack-omemo` + `smack-omemo-signal` 4.4.8 (or 4.5.0) | Apache-2.0 (Smack); pulls in the Signal library below | Smack is active, but OMEMO code changes little; legacy only | Brings its own `OmemoManager`, stores, PEP handling, threads and trust callbacks. Its `OmemoInitializer` takes over the `<encrypted/>` provider (see spike). Fencing it into Nema's attempt/generation model and Room storage means wrapping or bypassing most of it |
| B. `signal-protocol-java` plus a Nema-owned OMEMO 0.3 layer | GPL-3.0 (protocol and `curve25519-java`), BSD (`protobuf-java` 2.5.0) | Repository archived 2021; latest 2.8.1, Conversations ships 2.6.2 | Same structure as Conversations. Nema already owns the wire codec (`OmemoContentCodec`) and the provider. Nema adds PEP, storage, trust and pipeline stages |
| C. `libsignal` (Rust, current) | AGPL-3.0 | Active | Not usable. Since PQXDH, `session.rs` rejects pre-Kyber sessions ("X3DH no longer supported") and needs Kyber prekeys that OMEMO 0.3 bundles do not have. It also adds native libraries per ABI |
| D. Own OMEMO 2 implementation | n/a | n/a | Only needed when OMEMO 2 becomes a target. Python's `twomemo` is the only maintained reference implementation seen |

Licences are GPLv3-compatible with Nema (GPLv3).

**Approved library: option B**, `signal-protocol-java` plus a Nema-owned
OMEMO 0.3 layer. Nema already parses OMEMO itself and refuses foreign
providers, and Conversations proves this stack at scale. The risks are an
unmaintained crypto dependency (we own any patches) and an old
`protobuf-java`. The mitigation is to bound inputs before they reach
protobuf parsing; the existing provider already bounds evidence size. There
is no approved library fallback. A size or implementation blocker requires
a new decision, not substitution of option A or another library.

Spike results (branch `spike/omemo-webrtc-size`, not for merge):

- A JVM/Robolectric test sends a legacy OMEMO message from one device to
  another and decrypts it. The message goes through the Smack 4.4.8 elements
  and `signal-protocol-java` 2.6.2 X3DH and ratchet, with Conversations'
  AES-128-GCM key-and-tag layout, then gets a ratchet reply. It passes.
- The release APK (unminified) goes from 14,011,534 to 14,372,630 bytes
  (+353 KiB) with `smack-omemo-signal` and its dependencies (`smack-omemo`,
  `signal-protocol-java` 2.6.2, `curve25519-java` 0.4.1, `protobuf-java`
  2.5.0). No native libraries are added, since `curve25519-java` is pure
  Java. Option B is smaller than A because it drops `smack-omemo`.
- With `smack-omemo` on the classpath, Smack's `OmemoInitializer` registers
  `OmemoVAxolotlProvider` for `<encrypted/>`, and all seven
  `ProtectedMessageStoreTest` cases fail with "Unexpected protected provider
  owner". Adopting option A means replacing that guard deliberately (map
  seam 1).

## Identity, device and storage

- One OMEMO identity per account on this install: an identity key pair, a
  31-bit random device ID, a signed prekey, and 100 one-time prekeys
  (Conversations' count). Device IDs must not collide with our own device
  list.
- Tables, added empty first by map seam 4:
  - `omemo_identity` (account, device ID, wrapped private key, public key)
  - `omemo_prekeys`, `omemo_signed_prekeys`
  - `omemo_sessions` (account, peer JID, device, wrapped session record)
  - `omemo_devices` (account, JID, device, last seen in list, active flag,
    identity public key)
  - `omemo_trust` (account, JID, fingerprint, trust state, verification
    method, time)
- Private keys and session records are wrapped with a non-exportable
  AndroidKeyStore AES-GCM key per account, following
  `credentials/CredentialVault.kt`. The key does not require user
  authentication; otherwise a background service could not decrypt. Public
  keys and device lists are plain columns so queries stay simple.
- Backups are off (`allowBackup=false`). Reinstalling or clearing data
  creates a new device: peers see a new fingerprint and old messages stay
  unreadable. The UI says this at setup. Removing our stale device IDs from
  our own device list is a user action ("Clean up other devices"), as in
  Conversations.
- Account removal deletes the identity rows and the Keystore alias in one
  operation, and retracts our device from the account's device list when
  online.

## Trust model and UX

Alternatives considered:

1. **BTBV ("blind trust before verification")**, the Conversations default.
   New devices of a contact are trusted automatically until the user
   verifies at least one of that contact's devices. After that, new devices
   are untrusted until verified, and the UI flags them.
2. **Manual only.** Every new device must be verified before sending to it.
   This is safest, but most users stop using encryption.
3. **TOFU without verification.** Not recommended; there is no upgrade path.

**Approved trust model:** BTBV, plus QR verification that reads and writes
Conversations' `xmpp:` URI fingerprint parameters, and a fingerprint list
per contact with copy and compare. A message from a device the user has
marked untrusted is still shown, with a persistent "unverified device"
marker; it is never hidden. Fingerprints are shown in Conversations' format
so users can compare across clients. Slice 1 confirms the exact rendering
against Conversations.

Encryption state per chat:

- Conversations enables OMEMO by default for conversations its policy
  considers suitable, rather than after checking every bundle. Nema's
  approved policy: encryption is on by default for new 1:1 chats once the
  contact has published at least one device. Existing chats ask once.
  Sending then follows the trust filter below.
- An encrypted chat never falls back to plaintext on its own. If no device
  can be encrypted to (no device list, no bundles, all untrusted), the send
  fails with a reason and the user picks "Send unencrypted" explicitly.
  Map seam 3 makes this a property of the outbox row.

## Device lists, bundles and multi-device

- Announce `eu.siacs.conversations.axolotl.devicelist+notify` (map seam 6).
  Device-list updates arrive as PEP notifications (map seam 5).
- After login, fetch our own device list. If our device ID is missing,
  publish the list with it. Publish both the device list and the bundle with
  publish-options `pubsub#access_model=open`, so contacts without a presence
  subscription can discover our device IDs and start sessions. If the server
  rejects publish-options, reconfigure the node to the open access model and
  retry.
- Sending to a JID encrypts the key for every active, trusted (verified or
  BTBV) device of the recipient, plus every other active, trusted device of
  our own account, so carbons and other own clients can read it. Own
  devices pass the same trust filter as the contact's. Otherwise a
  malicious server could inject an "own" device and receive every future
  message key.
- Fetch a bundle when there is no session for a device, building a session
  per device with X3DH. A device that fails three bundle fetches is marked
  inactive for sending until it reappears in a list update.
- Prekey messages consume a one-time prekey. Replenish prekeys and
  republish the bundle after consumption, with the publish rate-limited.
- Session repair: when a message cannot be decrypted because the session is
  missing or broken, Nema fetches that device's bundle, builds a new session
  (X3DH), and sends one empty key-transport message (an OMEMO element with no
  payload) over it, so the peer also moves to the new session. A resend
  without the rebuild repairs nothing. Conversations limits this with a set
  of devices already attempted, which is cleared on reset. Nema uses the same
  rule, persisted per account.

## Decryption across delivery paths

One crypto stage (map seam 2) handles live, carbon, offline and MAM
envelopes, with its storage effects committed in the same transaction as
the message row.

- **Exactly once, atomically.** `SessionCipher.decrypt` advances and stores
  the session, and removes a consumed one-time prekey, before it returns.
  Ratchet message keys are deleted after use, so a stanza decrypted live
  fails if decrypted again from MAM (`DuplicateMessageException`). Two rules
  follow:
  - The Signal stores are Room-backed and run inside the same transaction as
    the plaintext message insert. A crash then leaves either both the
    advanced session and the plaintext, or neither. Running the stage before
    `MessageCore.ingest` is therefore not enough: the decryption call itself
    sits inside the ingest transaction, or writes a durable decrypted-result
    record in the same transaction as the session update.
  - Decryption is serialised per (account, peer device). Before decrypting,
    the stage looks up an existing row by stanza-id or origin-id and skips
    work if that row is already decrypted. A later failure never overwrites
    a decrypted row. Tests cover a crash between decrypt and insert, and a
    race between the live and MAM copies of the same message.
- **Out of order.** `signal-protocol-java` keeps skipped message keys
  (bounded) for each chain, so MAM pages that arrive out of order still
  decrypt. Catch-up decrypts oldest-first per conversation.
- **Carbons.** A sent carbon carries a key for our device only if the other
  own client encrypted to our device. Otherwise the result is "not
  encrypted for this device".
- **Offline delivery** arrives as live messages and needs no special path.
- **Before this device existed.** History older than our device can never
  be decrypted. The UI states that, rather than showing "could not be read".
- **MUC (later).** Only non-anonymous, members-only rooms, as in
  Conversations. Messages are encrypted to the devices of every member's real
  JID. This needs a member-list query and occupant real-JID tracking, which
  Nema does not have (see map, MUC). Not in v1.

## Encrypted files

XEP-0454 (0.1.0, Experimental) documents what clients already do:

- The sender encrypts the file with AES-256-GCM (tag appended), uploads it
  over HTTP Upload, and sends `aesgcm://host/path#<iv><key>` inside the
  encrypted body. The fragment is hex: a 12-byte IV followed by a 32-byte
  key. Older senders used a 16-byte IV, so the receiver accepts both.
- The receiver maps `aesgcm://` to `https://`, downloads, decrypts and
  verifies the tag before caching. The key and IV are never part of the
  cache key, logs or previews (map seam 9).
- The download size limit is applied before decryption. A tag mismatch is
  an explicit "file could not be decrypted" state, never a partial file.
- `aesgcm://` URIs are never linkified, copied as links, shared or opened in
  a browser (XEP-0454 security considerations). The fragment carries the
  key, so link and attachment actions use the decrypted local file only.

## Failure states that replace today's placeholder

`ProtectedState` keeps its meaning (syntax only). Decryption adds states
that the timeline, notifications and previews render.

| State | Shown as | Unread/notify |
|---|---|---|
| Decrypted, trusted or BTBV | Normal message, lock icon | Yes |
| Decrypted, untrusted device | Message plus "unverified device" marker | Yes |
| Not encrypted for this device | "Not encrypted for this device" | Yes, generic |
| Older than this device | "Sent before this device was set up" | No |
| No session / decryption failed | "Could not be decrypted" (repair sent) | Yes, generic |
| OMEMO 2 received | "Sent with OMEMO 2, not supported yet" | Yes, generic |
| Key transport or empty payload | Hidden (session maintenance) | No |
| Malformed | Existing `REJECTED` | No |

The "Unauthenticated fallback" body stays visible, and stays labelled, only
when decryption failed. Notifications for decrypted messages follow the same
content rules as plaintext messages. Whether to offer a "hide encrypted
content in notifications" option is a product decision.

## Interop test plan

Use dedicated test accounts on one server, plus an account on another
server for s2s.
Clients: Conversations (Android), Monocles (Android), Dino (desktop),
Gajim (desktop).

1. Nema to each client: first message (prekey path), reply (ratchet), 50
   messages each way, alternating.
2. Each client sends to Nema while Nema is offline: some messages arrive
   through offline delivery and others only through MAM catch-up. Messages
   that arrive by both routes are stored once, with the decrypted row kept.
   Clearing app data is a reinstall (case 4), not a way to re-decrypt
   history: consumed message keys are gone by design.
3. Nema with a second own client (Conversations on the same account): sent
   and received carbons decrypt on both.
4. Reinstall Nema: a new device appears; the peer's BTBV/verification UI
   reacts; old history shows "Sent before this device was set up".
5. Verification: scan Conversations' QR, and Conversations scans Nema's.
   Then a new peer device appears and must be flagged.
6. Files: send and receive `aesgcm://` images both ways, with 12-byte and
   16-byte IVs.
7. Breakage: delete Nema's session for a peer device. The repair message
   restores the session on the next message.
8. Negative cases: an OMEMO 2 message (where a client can send one), a
   malformed header, a duplicate stanza and an oversized payload all map to
   the states above.

Each case records the client version, the direction and a short log
excerpt with ciphertext and keys removed.

## First slices

1. Seams 1 to 4 from the map. No behaviour change.
2. Identity and device-list publication behind a developer flag. Receiving
   only: decrypt 1:1 live, carbon and MAM messages with BTBV, using the
   failure table.
3. Sending: per-chat encryption with no silent fallback.
4. Verification UI and QR.
5. `aesgcm://` files.
6. MUC, then OMEMO 2, when decided.
