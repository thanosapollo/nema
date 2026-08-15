# Nema

Nema is an experimental XMPP client for Android. It is built with Kotlin, Jetpack Compose, Smack, and Room.

The current work focuses on reliable plaintext messaging: direct and group chats, message archive recovery, Carbons, replies, drafts, bookmarks, blocking, vCards, and HTTP file upload. OMEMO and OpenPGP are not implemented. Do not treat this build as an encrypted messenger.

Nema is alpha software. Database migrations and messaging behavior receive extensive automated and device testing, but releases may still contain bugs or incomplete UI.

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17
- Android SDK 36

## Build

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Clone

```sh
git clone https://git.thanosapollo.org/nema.git
```

Browse the repository at <https://git.thanosapollo.org/nema/>.

## License

Copyright 2026 Thanos Apollo.

Nema is free software under the GNU General Public License, version 3 only (`GPL-3.0-only`). See [LICENSE](LICENSE).
