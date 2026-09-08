# Shared thread-directory experiment

This is a **real XMPP server proof**, not an Android integration or a production deployment. A small Prosody module provides durable shared metadata over authenticated XMPP IQs. Native XMPP still carries ordinary message bodies and XEP-0201 thread IDs; native MUC still owns membership and room lifetime.

The recorded run passed **55 assertions** against Prosody 13.0.6 and Slixmpp 1.12.0, including an independent rerun of the command below. The tested module's SHA-256 was `a678f50803759ac63f75214830f4bbc77b4d9076165919ea050e488834daa77a`. See [WIRE.md](WIRE.md) for the exact experimental contract. No MAM module or archive-query stand-in supplies directory results.

## Files

- `mod_thread_directory.lua`: the optional server feature; synchronous durable keyval operations, per-entry CAS, paginated snapshots, creator authority, native MUC incarnation and affiliation checks.
- `proof.py`: disposable server lifecycle and independent authenticated Slixmpp resources; every directory assertion reads actual server replies.
- `prosody.cfg.lua`, `mod_proof_accounts.lua`: **test-only** configuration and generated-account bootstrap. Never use these on a live server.
- `WIRE.md`: the versioned IQ contract and Smack integration rules.

## Run

Prerequisites: Docker access, OpenSSL, `uv`, and the already-built disposable server image:

```
sha256:712415a5b1156ad68929ab024ff04750420f98f60da6a47bdb088f4ddafc112a
```

This image is locally named `nema-m3-prosody-local:13.0.6`; the runner uses its immutable ID, not that mutable tag. It contains `/opt/prosody/bin/prosody`, source archive `/opt/prosody/source.tar.gz`, Lua 5.4 and the normal upstream modules. The runner verifies the source archive SHA-256 and compares installed MUC/storage files against that archive. It does not download or build an image automatically. Repetition on this host is supported; provisioning this local image on another machine is a separate prerequisite, not a published image claim.

From the worktree root:

```sh
flock -w 180 "${PROOF_LOCK:?Set PROOF_LOCK to a shared private lock-file path}" \
  env -u PYTHONPATH uv run --no-project experiments/thread-directory/proof.py
```

During other interactive work, use the existing shared resource slice:

```sh
systemd-run --user --scope --slice=nema-proof.slice \
  --unit=nema-thread-proof-manual \
  nice -n 10 ionice -c 3 \
  flock -w 180 "${PROOF_LOCK:?Set PROOF_LOCK to a shared private lock-file path}" \
  env -u PYTHONPATH uv run --no-project experiments/thread-directory/proof.py
```

Use a unique unit name for concurrent invocations. The server has its own Docker limits: **0.25 CPU, 512 MiB, 64 PIDs**, dropped capabilities and no-new-privileges. Only a random nonstandard `127.0.0.1` c2s port is published. The runner verifies that binding before use and again after restart. A normal Docker bridge is used; this is not an egress-isolation claim. No s2s, HTTP, HTTPS, MAM, GPU, production configuration or real account is used.

The XMPP scenario has a 240-second deadline; individual IQs and joins have 8-second deadlines. Docker subprocesses are bounded. The fixture gets fresh generated credentials and a one-day self-signed test certificate trusted only by these clients. Credentials never appear in command arguments or successful stdout. After every attempted launch, including a CLI timeout or nonzero start result, cleanup resolves the exact name and verifies its run-specific ownership label before pinning an immutable container ID. A foreign colliding name is never removed. Removal and absence readback use that ID, not the reusable name. Each cleanup command has a 10-second deadline (at most four commands); an ambiguous removal result still requires readback. Only settled cleanup permits deleting generated credentials, keys and storage. If launch was not acknowledged and no owned ID was ever pinned, an empty listing or foreign collision cannot establish completion of an in-flight create: cleanup reports unresolved and retains the fixture without polling for false certainty. This deliberately also retains fixtures for definite name-collision errors; the generic command boundary does not classify daemon rejection. If the daemon is unreachable or the owned target remains unresolved, the run fails explicitly with the exact name/known ID and preserves and reports the private fixture path for recovery. A hard kill cannot execute Python cleanup; recovery must verify the run's ownership label and immutable ID before removal, not remove a container merely by name. The shared flock serializes all real proof and fault runs; ensure its parent directory exists.

`PYTHONPATH` must be unset: the host can inject a different Slixmpp version ahead of uv's isolated dependency. The proof explicitly rejects a version mismatch. Its MUC join sets `pfrom` because this pinned Slixmpp/dependency combination treats an empty JID as truthy in its join waiter. Readiness requires an actual XMPP stream response, not merely Docker's early TCP listener. Docker can change a randomly published port on restart; the runner reads it back rather than assuming stability.

## Cleanup regressions

Deterministic tests exercise `main()` and `server_fixture()` at the launch boundary and cover initial absence after ambiguous launch, acknowledged launch with a previously pinned ID already absent, collision preservation, unreachable-daemon fixture retention, and failed-removal readback:

```sh
env -u PYTHONPATH uv run --no-project experiments/thread-directory/test_cleanup.py -v
```

Opt in to the additional disposable Docker cases under the same lock and slice:

```sh
systemd-run --user --scope --slice=nema-proof.slice \
  --unit=nema-thread-cleanup-manual nice -n 10 ionice -c 3 \
  flock -w 180 "${PROOF_LOCK:?Set PROOF_LOCK to a shared private lock-file path}" \
  env -u PYTHONPATH NEMA_CLEANUP_DOCKER_TESTS=1 \
  uv run --no-project experiments/thread-directory/test_cleanup.py -v
```

These cases create real resource-capped, network-disabled containers from the pinned image: a running container followed by an **injected CLI `TimeoutExpired`**, a created/nonstarted container followed by an **injected nonzero start result**, and a genuine Docker name collision with a different owner marker. They assert exact-ID absence or preservation before the foreign container's own test owner removes it and verifies absence. In the collision case, production cleanup must preserve the private directory. Only the test owner, having observed the completed genuine collision rejection and verified removal of its foreign container by exact ID, removes that exact empty fixture with `Path.rmdir()` and checks absence. This is not an unconditional production recovery recipe: for an unknown in-flight launch, retain the reported fixture until request settlement is established; a later empty listing or elapsed polling window is not sufficient. The initial-empty-listing case is a deterministic command-boundary fault model, not a live delayed-daemon-create reproduction. These tests do **not** claim a genuine daemon timeout or daemon start malfunction was reproduced.

## Proven journeys

- Two account identities plus a second creator resource and an outsider authenticate over locally verified TLS.
- Capability discovery distinguishes a supported empty directory from unsupported service behavior.
- Empty named threads are found by the other direct participant and a native MUC member before any message is sent.
- Creator rename works from another resource; other participants and outsiders cannot seize authority. Reusing a thread ID across conversations cannot overwrite the original item.
- A simultaneous two-resource CAS race yields one success and one conflict. Last-operation retries are bounded and idempotent; an older create retry after rename conflicts instead of reverting it.
- All empty/archived items survive complete retrieval. Mid-pagination changes cause conflict, not torn snapshots. At capacity, all 128 entries are read across 43 pages; the next create is rejected without evicting old or unrelated entries.
- Native room affiliation revocation takes effect on the next request, while nonjoined affiliated members can retrieve. Returned directory items do not disclose creator JIDs.
- Direct and MUC bodies travel in both directions with exact core thread IDs. Main messages have no thread. Outsiders receive no observed directory broadcasts or test bodies.
- Actual server restart and fresh client instances preserve exact direct/MUC metadata. An offline client's missed rename/archive is recovered without MAM; archive is reversible.
- Destroying/recreating the same room JID isolates old state. Old snapshot and mutation incarnation tokens fail; the former owner has no rights in the new room. Ordinary non-members-only rooms are explicitly unsupported.

## Optional independent-client hook

For a separate Smack/JVM proof, set `NEMA_THREAD_PROOF_HOOK` to an **absolute executable path**. After the Python scenarios finish, while the same disposable server is still alive, the runner executes:

```
/absolute/hook /private/temporary/client.json
```

The hook has a 120-second timeout and inherits the runner's CPU slice/nice/IO priority. Only the private JSON pathname is passed in argv. JSON fields are `host`, `address`, `port`, `namespace`, `ca_certificate`, `accounts` (username -> generated password), and `muc_host`. Do not print the file or credentials. Trust only its test CA for this connection; keep server identity (`proof.test`) separate from endpoint (`127.0.0.1`).

Use **Bob/Eve** for a new direct pair (the Alice/Bob directory is deliberately full after the capacity test). Create a uniquely named new persistent members-only test room through native MUC if testing group behavior. The earlier `directory` room has deliberately changed lifetime/configuration. All Python resources are disconnected before the hook. Returning zero only marks hook execution successful; the hook must assert/read back its own real XMPP behavior before claiming Smack support. The recorded 55-assertion run did not execute a hook, so Smack is **not proven by this artifact**.

## Limits and next boundary

- One local authority only: existing local-local direct pairs and persistent members-only rooms on its configured local MUC component. Federation, authority migration and public-room guest semantics are unsupported.
- Metadata is not end-to-end encrypted. No push protocol; supporting clients refresh on entry/reconnect/mutation or explicitly. Unknown outcomes require readback, not silent overwrite.
- Native internal keyval storage uses atomic file replacement, and acknowledgement follows successful storage. This proves regular process restart persistence, not power-loss durability, disk-full fault injection, HA/multiwriter safety, backups or storage corruption recovery.
- Destroyed-room stores become inaccessible through the incarnation key; garbage collection is deferred. Account deletion/recreation lifecycle, global account quotas and abuse throttling need further design before production use.
- No mobile Room schema, durable Android outbox, Smack provider, notification flow, MAM/Carbons preservation, external legacy GUI client, device or APK was tested here. This cannot be relabeled a complete Nema end-to-end feature.
- No commits, staging, publishing, live-server deployment or unrelated worktree edits belong to this experiment.

## References

- [XEP-0201](https://xmpp.org/extensions/xep-0201.html): message grouping, not shared directory metadata.
- [XEP-0060 §7.1.3](https://xmpp.org/extensions/xep-0060.html#publisher-publish-error): authorized publishers may overwrite another item ID; generic publishing rights are not creator-only CAS.
- [XEP-0045](https://xmpp.org/extensions/xep-0045.html): native room affiliations and lifecycle.
- [Prosody module API](https://prosody.im/doc/developers/moduleapi).
- [Prosody 13.0.6 source](https://prosody.im/downloads/source/prosody-13.0.6.tar.gz), SHA-256 `ec696f9cf562c3af4a04b07d3fb36a1cedcc4e69a392fddcfc524bc67d93050f`.
