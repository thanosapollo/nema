# Contributing

Nema is under active development. Small, focused patches are easiest to review.

Before sending a patch:

1. Describe the user-visible behavior and failure case.
2. Add a regression test before changing behavior.
3. Run `./gradlew testDebugUnitTest assembleDebug lintDebug` with JDK 17.
4. Keep generated Room schemas in sync with database changes.
5. Do not include credentials, account data, device databases, build outputs, or editor state.

Send patches through the project tracker linked from the repository page. Security reports belong in the private channel described in [SECURITY.md](SECURITY.md).
