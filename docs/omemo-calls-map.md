# OMEMO and calls: architecture map and seams

Status: foundation notes, October 2026. Line references are pinned to commit
`1087d5b` and are relative to `app/src/main/java/org/thanosapollo/nema/`
unless they start with `app/`. Re-check them before editing; `cockpit: nema`
is refactoring this code.

Design documents: [omemo.md](omemo.md) and [calls.md](calls.md).
Tracker: OMEMO epic #81 (slices #82-#85), calls epic #86 (slices #87-#89).
Seam 1 is #82, seam 4 is #83, seams 2-3 are #84, seams 5 and 7 are #87,
and seam 6 is #88.

## What exists today

Nema has no cryptography beyond TLS and no real-time media. It recognises
OMEMO on the wire and stores it honestly as unsupported
([protected-content.md](protected-content.md)). Neither feature has a Jingle,
PEP-notification, IQ-handler or entity-capabilities path today.

### Connection and session

- `xmpp/smack/SmackSessionConnection.kt:160` `SmackSessionConnectionFactory`
  builds one `SmackSessionConnection` per attempt (`:207`, class at `:323`).
  Smack configuration is assembled at `:236-262` (SASL, TLS mode, host
  verifier, optional Tor socket factory).
- `:268-276` advertises disco features: replies, receipts, markers, chat
  states, corrections, reactions, RTT, occupant-id. This is the only place
  that announces client capabilities. There is no XEP-0115 entity caps
  handling for remote contacts. Calls and OMEMO device discovery both depend
  on it.
- `:404-408` per-connection setup: `installNemaOmemoProviders()`, automatic
  receipts disabled, one stanza listener for all messages. There are no IQ
  request handlers and no PEP (`+notify`) listeners.
- `xmpp/smack/ReconnectingSmackSession.kt:18` wraps reconnects
  (`connect :78`, `reconnect :88`). `xmpp/smack/OnionXmppConnection.kt:20`
  and `xmpp/smack/TorSocketFactory.kt:22` route accounts over Tor. Calls must
  be disabled on Tor accounts: ICE would reveal the device's addresses.
- `session/ActiveSessionController.kt:154-238` `SessionConnection` is the
  narrow interface the rest of the app sees: `send`, `sendSignal`,
  `sendReaction`, `sendChatState`, archive queries, vCard, blocking, HTTP
  upload and fetch, MUC join, bookmarks. It has no generic IQ, PEP publish,
  PEP subscribe or presence-to-full-JID primitive.
- `session/ActiveSessionController.kt:98` `SessionEvent` is a closed set
  (`ConnectionLost`, `Incoming`, `Signal`, `ChatState`, `RealTimeText`,
  `Reaction`, `OutgoingFailure`, `RoomUpdated`, `RosterSnapshot`). Dispatch
  happens at `:770-782`, and durable events go through `handleDurableEvent`
  (`:806`).
- `session/ActiveSessionController.kt:497-512` `send()` checks the dispatch
  lease against account and generation, then calls the connection.

### Service lifecycle and notifications

- `service/XmppConnectionService.kt:1353` is the single foreground service,
  started at `:1471`. Manifest: `app/src/main/AndroidManifest.xml:34-37`
  declares `foregroundServiceType="remoteMessaging"`. Permissions at `:3-8`
  include no `RECORD_AUDIO`, `CAMERA`, `MANAGE_OWN_CALLS`,
  `USE_FULL_SCREEN_INTENT` or phone-call/microphone FGS types.
- `service/XmppConnectionService.kt:159-175` wires the adapters
  (`LiveMessageAdapter`, `OutboxDispatcher`, `ArchiveSynchronizer`). Live
  ingest is called at `:235`.
- Channels are created at `:1372-1388`. Incoming message notifications are
  built in `service/IncomingMessageNotify.kt:20` (channel) and `:36`
  (notification). `:14` and `:44` substitute `protectedStatus()` for the
  body, so protected text never reaches a notification.
- Backups are off: `AndroidManifest.xml:15-17` (`allowBackup=false`,
  `fullBackupContent=false`, data-extraction rules). Key material inherits
  this, and a reinstall is a new OMEMO device.

### Message ingress

- One listener (`SmackSessionConnection.kt:408`) classifies direct, carbon
  and MUC carriers. Bodyless signals, chat states, RTT and reactions are
  skipped when the stanza holds OMEMO (`:371`, and `toIncomingSignal :1534-1538`,
  `toIncomingChatState :1584-1590`, `toIncomingRtt :1660-1664`,
  `toIncomingReaction :1680-1686`).
- `SmackSessionConnection.kt:1744` `Message.toIncomingEnvelope` builds the
  `IncomingMessageEnvelope` (`xmpp/transport/XmppTransport.kt:160`). It
  attaches `protection = protectedContent(...)` at `:1764` with a carrier
  kind (`LIVE`, `RECEIVED_CARBON`, `SENT_CARBON`, `MAM`;
  `xmpp/omemo/ProtectedContent.kt:15`).
- Carbons: `:1944` refuses bodyless carbon effects for protected stanzas.
  Carbon enablement is at `:1251`.
- MAM: `:2054` `normalizeMamResults`. `:2092` excludes protected stanzas from
  the non-body effect paths, and `:2096` builds envelopes for archived
  messages through the same `toIncomingEnvelope`. Paging is driven by
  `chat/ArchiveSynchronizer.kt:72` (`synchronize :121`, `backfillOnePage
  :219`, commit `:183/:262`).
- MUC: `joinMuc` `:831-849` and room listeners. Room messages use the same
  envelope path. Occupant real JIDs are not resolved to a stored set, which
  OMEMO in MUC needs (non-anonymous rooms only).
- `chat/LiveMessageAdapter.kt:11-19` converts an envelope to an
  `IncomingMessage` and calls `MessageCore.ingest`
  (`storage/MessageCore.kt:2455`, transaction body `:2628`).

### Protected-content placeholder

- `xmpp/smack/NemaOmemoProvider.kt:30` parses `<encrypted/>` for both
  namespaces into `NemaOmemoElement`. `:89-95` `installNemaOmemoProviders()`
  refuses any other provider owner with `check(...)`. `:97` is
  `Message.hasProtectedContent()`.
- `xmpp/omemo/OmemoContent.kt:7-10` `OmemoProtocol { LEGACY, MODERN }`.
  `:13-28` `OmemoContent` / `OmemoRecipientKey` keep unauthenticated wire
  evidence (sid, rid, prekey flag, ciphertext, IV, payload), with codec at `:40`.
- `xmpp/omemo/ProtectedContent.kt:8-12` `ProtectedState` covers syntax only:
  `UNSUPPORTED_PAYLOAD`, `UNSUPPORTED_HEADER_ONLY`, `REJECTED`. `:62` is the
  JSON evidence codec, `:207` re-serialises evidence as XML, and `:220`
  `protectedStatus()` is the user-visible text.
- `storage/ProtectedMessages.kt:6-36`: `isProtected`, `protection`,
  `protectedCompatible`, `preflightProtectedMerge`.

### Room schema

- `storage/NemaDatabase.kt:274` schema version 31, `exportSchema = true`
  (`:286`). JSON schemas are in `app/schemas/`.
- `storage/MessageEntities.kt:240-241` `messages.protectedState` (default
  `'NONE'`) and `protectedEvidence` (nullable JSON).
- `storage/MessageCore.kt` excludes protected rows from corrections,
  reactions, receipts, markers, identityless repair and alias merges (for
  example `:2220-2248`, `:2479-2600`, `:2804-2829`, `:2939`, `:2986`,
  `:3671`, `:3946`). Previews blank protected bodies at `:3552`. SQL views
  map `NONE`-with-evidence to `REJECTED` at `:1038` and `:1258`.
- No table holds identities, devices, sessions, prekeys, trust or calls.

### Egress

- `chat/OutboxDispatcher.kt:36`, `dispatch :149`, leases an outbox row and
  calls `ActiveSessionController.send` (`:497`), which reaches
  `SmackSessionConnection.send` (`:476-503`). That method converts with
  `OutgoingMessageEnvelope.toSmackMessage()` (`:1501`) inside an entry
  interceptor that enforces attempt and generation.
- `xmpp/transport/XmppTransport.kt:339-365` `OutgoingMessageEnvelope` has a
  `WIP-FOUNDATION: crypto/protection later` marker at `:360`. It carries
  plaintext `body` and attachment URL fields only.

### Attachments

- Upload: `SmackSessionConnection.kt:800-807` (`HttpFileUploadManager`) and
  `xmpp/httpupload/HttpFileUpload.kt`, `OutgoingAttachmentPreparation.kt`.
- Download: `xmpp/httpupload/AttachmentDownload.kt:17-19` accepts only
  `https`. `aesgcm://` is rejected today, and `:35-83` caches by URL.
- Key custody precedent: `credentials/CredentialVault.kt:54-159` wraps
  secrets with an AndroidKeyStore AES-GCM key. OMEMO storage can reuse this
  pattern.

## Seams for `cockpit: nema`

These are ordered. Each one is useful on its own and needs no crypto or media
dependency. None changes user-visible behaviour.

1. **Provider ownership seam.** `installNemaOmemoProviders()`
   (`xmpp/smack/NemaOmemoProvider.kt:89-95`) refuses foreign providers. The
   spike showed that adding `smack-omemo` registers `OmemoVAxolotlProvider`
   through Smack's optional `OmemoInitializer`. All seven
   `ProtectedMessageStoreTest` cases then fail with "Unexpected protected
   provider owner". Make ownership explicit: one Nema-owned provider that
   either delegates to a library parser or stays the inert parser, chosen at
   install time, with a test that the chosen owner wins regardless of class
   initialisation order. Files: `NemaOmemoProvider.kt`, `NemaApplication.kt:67`,
   `SmackSessionConnection.kt:406`.
2. **Crypto stage in ingress.** Add one stage that runs on live, carbon and
   MAM envelopes alike, on the path from `toIncomingEnvelope` through
   `LiveMessageAdapter.ingest` and the archive commit into
   `MessageCore.ingest`. It is invoked inside the ingest transaction, not
   before it. Decryption advances ratchet state, and that state must commit
   atomically with the plaintext row (see omemo.md, "Exactly once"). Today
   the stage is the identity function. Later it replaces `protection` with a
   decrypted body plus an authentication-state record, before dedup, reply
   and correction logic run. Files: `chat/LiveMessageAdapter.kt`,
   `chat/ArchiveSynchronizer.kt`, `storage/MessageCore.kt:2455-2628`,
   `service/XmppConnectionService.kt:159-235`.
3. **Crypto stage in egress.** Give `OutgoingMessageEnvelope` an explicit
   `encryption` policy (`NONE` now) recorded at enqueue time and stored with
   the outbox row, so a message chosen as encrypted can never be retried in
   plaintext. Replace the `WIP-FOUNDATION` marker at `XmppTransport.kt:360`.
   Files: `XmppTransport.kt`, `OutboxDispatcher.kt`, outbox schema.
4. **Schema reservation, one migration.** Add a `messages.encryption` column
   (`NONE`, `OMEMO_LEGACY`, `OMEMO_2`), a nullable `senderDevice`, and an
   authentication state (`UNTRUSTED`, `BLIND_TRUSTED`, `VERIFIED`,
   `UNAUTHENTICATED_FALLBACK`). Keep `protectedState` for undecryptable rows.
   Add empty `omemo_identity`, `omemo_devices` and `omemo_trust` tables with
   no writers. Files: `storage/MessageEntities.kt`, `NemaDatabase.kt`,
   migration test. Doing this in one migration saves a second schema bump
   during feature work.
5. **Session primitives.** Extend `SessionConnection`
   (`ActiveSessionController.kt:154`) with three generation-guarded
   primitives: `sendIq(request): IqResult`, `publishPep(node, item,
   publishOptions)` and a PEP-notification event (`SessionEvent.PepItem`).
   Both features need them: OMEMO device lists and bundles, ExtDisco
   credentials, and Jingle IQs. Also decide whether Bookmarks 2 publishing
   (`SmackSessionConnection.kt:916`) should pass `publish-options`
   (`pubsub#access_model=whitelist`) through the same primitive. It does not
   pass any today.
6. **Capability registry.** Move `:268-276` into one table of
   `feature -> enabled(account)`. OMEMO adds
   `eu.siacs.conversations.axolotl.devicelist+notify`. Calls add the Jingle
   features, and those must be withheld on Tor accounts. Add XEP-0115 caps so
   contacts see the features. Nema also needs to read remote caps to show a
   call button.
7. **IQ request handler seam.** One registration point for inbound IQ
   handlers with attempt/generation fencing, equivalent to the message entry
   interceptor at `:476-503`. Jingle needs it first.
8. **Second foreground-service role.** Split the service's notification and
   FGS-type ownership so a call can run as `phoneCall` (or
   `microphone|camera`) alongside `remoteMessaging`, without the messaging
   lifecycle owning call teardown. Files: `XmppConnectionService.kt`,
   manifest.
9. **Attachment scheme seam.** Make `httpsAttachmentUrl`
   (`AttachmentDownload.kt:17`) return a typed source (`Https(url)` or later
   `AesGcm(url, key, iv)`) and keep the decryption key out of the cache key
   and logs. Upload gains a pre-upload transform hook in
   `OutgoingAttachmentPreparation.kt`.
