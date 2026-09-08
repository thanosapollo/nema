# First-class threads

Status: behavior contract and experimental proof in progress. This document describes the intended behavior, not a claim that all of it is implemented. The proof ledger below separates working evidence from outstanding work.

## Goal

Every direct conversation and groupchat has a permanent **Main** destination and a discoverable list of persistent, named threads. A thread can represent a project, a task, or a topic. Opening it must feel like switching conversations, not searching contact details for a hidden feature.

This is a flat, shared conversation structure built on XMPP message threads. It is not a new transport, a separate room per thread, or a private subgroup.

## User-visible behavior

### Finding and navigating threads

- Put the current destination and a clearly labeled thread switcher directly in the chat screen, below the conversation identity. Main is visible even when no named threads exist.
- Open the thread list with one tap, without entering contact or room details. Keep Main first and show the selected destination, thread names, unread activity, and a visible New thread action.
- Use the existing native Compose typography, colors, and accessible controls. Preserve 48dp interaction targets and avoid squeezing an unbounded number of tabs into the header.
- Selecting a destination changes the live route immediately. It does not wait for archive fetching or recreate the Home conversation list.
- Each destination retains its own draft, attachments, reply/edit state where supported, and viewport. A delayed send or failure settles only the destination and draft revision that originated it.
- Back dismisses a popup or keyboard first, then returns from a thread to Main, then from Main to Home. It restores the previous viewport instead of jumping to the newest message.
- The composer identifies its destination. A thread notification must open that exact account, conversation, and thread rather than silently landing in Main.

### Main and compatibility

- Main always exists, cannot be renamed or archived, and is the default for ordinary conversation entry.
- Main is not an aggregate All messages view. Messages belonging to an explicitly named thread remain in that thread; activity indicators keep them discoverable.
- Messages without a thread ID belong to Main. Existing implicit direct-chat session IDs must not become a new named channel for every legacy client session.
- Preserve existing XEP-0201 thread history and reply relationships. Migration must not move or erase messages based on a display name.
- Unsupported clients still receive ordinary readable message bodies. They may show messages in one combined timeline and need not support the directory UI. Do not claim isolated channels or shared names on those clients.
- Unknown, malformed, or unauthorized directory metadata cannot hide an otherwise valid message or rename an unrelated thread.

### Creating and naming

- New thread accepts a human-readable name and creates a stable opaque ID. A thread is discoverable even before its first message.
- Names are shared with participants and their supporting clients, not private annotations. The creation/rename UI must communicate this; never publish existing local aliases silently.
- Renaming preserves the ID, history, unread state, and any future agent session association. Duplicate names are allowed; names do not identify a destination.
- Names must be nonblank after trimming, bounded, single-line, and rendered as plain text. Validation failures preserve the input and explain the correction.
- Creation or rename while offline must either have a durable pending operation with visible retry/failure state or clearly remain unavailable. A local database write alone is not Shared or Synced.

### Membership and lifecycle

- A direct thread belongs to the same pair of accounts as its parent conversation. A group thread inherits the room's membership and visibility.
- A title or thread ID grants no authority. Accept metadata only from an authenticated source entitled to create or change it within that exact conversation.
- The initial shared metadata model must specify creation, rename authority, revision ordering, duplicate delivery, and conflict recovery before enabling network writes.
- Archive is reversible and preserves messages. An archived thread is reachable through an explicit archived view; incoming activity remains discoverable. Main cannot be archived.
- Nested child-thread creation, per-thread room membership, and private subchannels are outside the initial proof. Existing child lineage remains readable.

## Protocol boundary

Use the core message `thread` element and XEP-0201 for message grouping. Keep message IDs, thread IDs, and titles separate. Scope local identity by account, canonical peer/room, message kind, and stable thread ID.

A named-thread directory is additional metadata, not functionality supplied by XEP-0201 alone. The experimental extension needs its own documented namespace and version, discovery behavior, authenticated mutation rules, and retrieval contract. Do not invent an official XEP number.

A fresh authorized client must be able to retrieve named threads and their latest metadata without downloading the entire message archive. Live notifications alone are insufficient: empty threads, offline renames, reconnects, and late joiners need durable recovery. Unsupported server capabilities must produce an explicit limitation, not a local-only feature labeled as synchronized.

The wire/storage choice is an experiment to be settled by the proof. Record the selected design and actual server requirements here before claiming protocol interoperability. Compare existing XMPP facilities before adding a service or custom server module.

## Agent foundation, deliberately deferred

A future adapter can bind an authenticated account + conversation + stable thread ID to a durable agent session. Renaming must not invalidate this identity. Replies, progress, and attachments must retain their originating destination.

This Nema proof does **not** implement Hermes session handoff, select a working directory, grant filesystem access, change an agent profile, or alter hermes-xmpp. A shared title is untrusted display metadata, never an instruction or project-access grant.

## Proof sequence and environment boundary

The proof may use an optional server extension in an isolated test environment. This does not authorize deployment or configuration changes on a live XMPP service. Ordinary messaging must continue to work without that extension.

1. **Android interaction proof:** make Main and a durable local named-thread list first-class in direct and group chats. Until the wire integration exists, label naming as local and do not claim synchronization. Prove actual presenter/storage/UI behavior, not a disconnected mock screen.
2. **Shared-directory protocol experiment:** use a disposable local XMPP server and genuine authenticated clients to prove empty creation, authorized metadata changes, independent directory retrieval, room access, conflicts, and restart recovery. Document the exact experimental wire contract and server requirements. Synthetic server responses do not count.
3. **Integrated proof:** connect Nema to that experimental authority, then exercise the complete multi-client Android/network journey below. Separate server-prototype success from actual Nema interoperability.

These are cumulative evidence stages, not alternate definitions of completion. A local UI or standalone directory experiment alone does not satisfy the complete proof.

## Acceptance journey

1. Open an existing direct chat. Find Main and New thread without opening contact details. Create a named thread before sending a message.
2. Switch Main -> thread -> Main. Verify separate visible history, retained drafts and attachments, correct Back/IME behavior, and no delayed-send cross-talk.
3. Open the same conversation in another supporting client instance. Retrieve the empty thread and its shared name. Rename through an authorized participant and observe the other instance converge without losing history.
4. Send messages in Main and the named thread in both directions. Verify exact outgoing thread IDs, live delivery, Carbons, and archive replay retain the same destinations without duplicates.
5. Repeat creation, directory retrieval, naming, and isolated messaging in a test groupchat with authorized test participants only.
6. Disconnect a client, rename or archive elsewhere, then reconnect. Restart the app and repeat with a fresh local database in an isolated test installation. Verify durable directory retrieval without a full archive scan.
7. Try duplicate/reordered metadata, invalid names, unknown thread references, cross-account/room collisions, and an unauthorized rename. Verify fail-closed metadata handling without losing readable chat messages.
8. Receive traffic from an unsupported client. Verify readable fallback and Main behavior without manufacturing a named thread for every implicit session ID.
9. Exercise notifications, unread indicators, rapid switching, keyboard, and process recreation on Android. Record measured warm switching behavior and a human usability verdict separately from unit-test results.

## Proof ledger

- Baseline: XEP-0201 IDs/parent lineage, local thread titles, and thread routes exist. Local renaming only writes Room; it is not shared-name synchronization.
- Behavior contract: documented here; implementation and wire design are being investigated.
- First-class chat navigation: not yet demonstrated for this candidate.
- Shared directory and metadata: not yet demonstrated.
- Direct and groupchat network interoperability: not yet demonstrated.
- Cross-device/offline/fresh-install recovery: not yet demonstrated.
- Physical Android usability: not yet demonstrated.

Update this ledger with the exact tested candidate, commands/scenarios, observed outcomes, and unresolved limitations as the proof develops. Keep credentials, real addresses, private messages, and raw device captures out of tracked documentation.

## References

- [XEP-0201: Best Practices for Message Threads](https://xmpp.org/extensions/xep-0201.html)
- [XEP-0045: Multi-User Chat](https://xmpp.org/extensions/xep-0045.html)
- [XEP-0060: Publish-Subscribe](https://xmpp.org/extensions/xep-0060.html)
- [XEP-0223: Persistent Storage of Private Data via PubSub](https://xmpp.org/extensions/xep-0223.html) (account-private storage, not participant-shared metadata by itself)
