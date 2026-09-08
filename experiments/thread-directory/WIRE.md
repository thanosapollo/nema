# Experimental wire: urn:nema:threads:0

Small Prosody module, native authenticated XMPP IQ request/response plus synchronous Prosody keyval durable storage and native MUC affiliations. No new transport/official XEP/per-thread room. Standard PubSub supports durable items but generic publisher rights allow overwriting others' IDs; neither generic node ACL nor PEP supplies room-membership inheritance plus per-creator compare-and-swap semantics by itself.

## Authority and scope

IQ target: authenticated account's local server domain (`proof.test` in disposable fixture). Disco feature `urn:nema:threads:0` is the explicit experimental capability; absent capability means UNAVAILABLE, never empty. Only direct pairs with two different existing local accounts supported. No federation discovery or cross-domain hosting claim. MUC only configured local component, persistent members-only rooms; other room types return not-allowed. Each operation rechecks native owner/admin/member affiliation, including offline members; revoked members denied. Room incarnation is keyed by durable native room salt (rotates on destruction/recreation), preventing old metadata from leaking to a new room at same JID. Old storage GC is deferred.

Creator authenticated bare JID remains **server-private**. Returned items expose only `can_modify='true|false'` for this requester, never creator JID or public-salted hash. No broadcast/push; refresh explicitly on entry/reconnect/after mutation or manually.

## Payloads

`<directory xmlns='urn:nema:threads:0' kind='direct' a='alice@proof.test' b='bob@proof.test' op='list'/>`, IQ get. Canonical bare-JID participant order is immaterial internally. For MUC replace a/b with `kind='muc' room='directory@rooms.proof.test'`. Actor must be authorized for explicit exact scope; ID knowledge grants nothing.

List result: same directory wrapper with scope attributes, `snapshot='<opaque token>' total='N' complete='true|false'` and optional `after='<last UUID>'`; child `<thread id='<lowercase UUID>' title='...' revision='1' archived='false' can_modify='true'/>`. Includes empty and archived, never Main. Fixed page size3, hard directory quota128. Continuation request must echo after + snapshot. Any mutation/room-incarnation change causes conflict on stale snapshot. Stage all pages, enforce constant snapshot/total, unique IDs, cursor progress and final exact count; only then atomically replace this account+conversation directory. Errors preserve previous cache. No MAM needed; no server item eviction at capacity (resource-constraint instead).

Create IQ set: same scope, `op='create' id='<caller-generated lowercase UUID>' operation='<fresh lowercase UUID>' title='...'`.
Rename IQ set: `op='rename' id='...' operation='...' expected='<entry revision>' title='...'`.
Archive/unarchive IQ set: `op='archive' id='...' operation='...' expected='...' archived='true|false'`.

MUC list and mutation replies additionally return `incarnation='<opaque token>'`. Every MUC mutation MUST echo the incarnation observed when that action was authored; absent/stale token returns conflict. Never transparently rebase a queued mutation onto a recreated room. Direct requests omit incarnation. This prevents an old queued create from publishing its old title to new members at the same room JID.

Successful mutation reply returns one resulting thread plus current snapshot/total, echoed operation and replayed boolean. Entry revisions start1, increment every acknowledged new mutation. Server serialization + per-entry expected revision makes first matching writer win; others receive conflict. Unrelated entries cannot be lost by stale directory replacement. No timestamp ordering.

Only the **last** operation ID+exact payload per entry is retained. Retrying that operation returns unchanged result (`replayed=true`); same ID with different payload conflicts. After a later operation, an old create conflicts and old update revision conflicts; retrieve and reconcile, never silently rebase. This is bounded last-operation retry help, NOT an unbounded exactly-once receipt ledger. Caller scopes operation identity to the entry and never reuses it for a new action. Unknown outcomes require readback; do not blindly overwrite.

Titles: nonblank Unicode plain text; at most128 Unicode code points/512 UTF-8 bytes, no control characters or Unicode line separators. Client trims names; server rejects leading/trailing ASCII space. Duplicate titles allowed. UUIDs remain stable across rename/archive; archive reversible, no delete.

## Messages and limits

Ordinary body + core `<thread>UUID</thread>` (XEP-0201) unchanged; no title as ID. No named-thread parent field. Main synthetic/no explicit named-directory ID. Unsupported clients read ordinary bodies; this prototype is not a Smack/Android integration, push protocol, MAM preservation proof, E2EE feature, HA store, power-loss durability test or production deployment.

Real-network proof: the recorded bounded Prosody 13.0.6 / Slixmpp 1.12.0 run passed 55 assertions. See experiment `README.md` for the tested module hash, repeatable command, scope and remaining integration gates. The server module is still experimental, not production-ready or an official XEP.
