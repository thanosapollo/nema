# AGENTS.md

Public guidance for contributors and coding agents working on Nema.

## Project

Nema is an experimental Android XMPP client in Kotlin, Jetpack Compose,
Smack, and Room. It targets reliable plaintext messaging. OMEMO and
OpenPGP are not implemented.

Source lives under `app/src/main`. Tests live under `app/src/test`.
Keep exported Room schemas in `app/schemas` in sync with database changes.

## Architecture

- Room owns messages, peers, drafts, outbox, and archive cursors.
- The conversation list is a projection of messages and peers, not a
  separate table.
- Navigation is in-memory. Persist the route after the screen flips.
  Room is not the live source of truth for which screen is showing.
- Keep Home composed under an open chat. Do not tear the list down to
  open or close a conversation.
- Fetch identities for the open peer only. Do not crawl the whole list
  on the UI thread.

## Delivery

Commit reviewed source before building or installing an APK.

1. Stage only the slice under review.
2. Keep the candidate at or under 350 added+deleted lines of maintained
   source, tests, config, and docs.
3. Run focused tests, then an independent review of the exact staged
   tree. Commit only on PASS.
4. Build the APK from that commit. Record the commit and APK SHA-256.
5. Install with `adb install -r` and keep existing app data.

Do not install from a dirty or unreviewed worktree. Do not wipe device
data. Do not push unless publication is explicitly requested.

## Verification

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Contributions

Send patches to patches@thanosapollo.org with a subject like
`[PATCH nema] Short description`.

Send bugs to bugs@thanosapollo.org with a subject like
`[BUG nema] Short description`.

See `CONTRIBUTING.org` for the full patch and bug-report process.
