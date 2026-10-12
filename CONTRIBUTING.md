# Contributing to Libreta

Thank you for helping with Libreta. This page explains how to build and test the project, which constraints your
change has to respect, and what a good change looks like.

## Build and test

1. Set up the toolchain and build the app. See [Build and install](docs/setup/build.md).
2. Run the automated checks before you send a change:

   ```bash
   ./gradlew :core:test :app:testDebugUnitTest
   ./gradlew :app:lintDebug
   ```

3. If your change touches the screens, the pen, sync, TLS or the AI, also run the relevant steps of the
   [manual checklist](docs/testing.md#manual-checklist-on-a-tablet) on a tablet or an emulator.

[Testing](docs/testing.md) lists what each test suite covers. A change that fixes a bug should come with a test that
fails without the fix. A new feature should come with tests for its logic.

## Respect the API 19 constraints

Libreta runs on Android 4.4 (API 19) on Dalvik, with 64 MB of heap per app. See
[Compatibility](docs/reference/compatibility.md) for the reasons. In practice:

- **No AndroidX and no AppCompat.** The app uses the framework classes only. Check a library's `minSdk` and its
  dependencies before you add it. OkHttp stays on the 3.12 branch and Conscrypt on 2.5.x for this reason.
- **`:core` is plain Kotlin on the JVM.** It must not depend on Android. Animal Sniffer checks `:core` against the
  API 19 signature and fails the build if you call something the tablet lacks, such as `java.time` or
  `Map.getOrDefault`.
- **Android lint runs `NewApi` and aborts the build** on errors in `:app`. Do not suppress `NewApi`. Guard the call
  with a `Build.VERSION.SDK_INT` check, or use another API.
- **Mind memory.** Do not decode large bitmaps on the main thread, keep results small, and reuse objects on hot
  paths such as drawing.
- **Do not block the main thread.** Network, disk and AI calls run on background threads.

## Code style

- Match the surrounding code: naming, file layout, the way errors are reported. The project has no formatter
  configuration, so consistency with the file you edit is the rule.
- Write KDoc comments for classes and non-obvious functions. A comment should explain **why** the code does what it
  does, not repeat what the code says.
- Keep a change focused. Do not mix a refactor with a behavior change.
- The app's user interface is in Spanish. Add new texts to `strings.xml` in Spanish. Code, comments and documentation
  are in English.

## Update the documentation

A change in behavior must update the documentation in the same commit:

- Update the matching page in `docs/` (see the [index](docs/README.md)): the guide that describes the feature, the
  [settings](docs/reference/settings.md), the [scripts](docs/reference/scripts.md) or the design page that explains
  the decision.
- Write for the reader, in the present tense and in English. Describe how things are, not how they changed. Keep the
  reasons for a design decision in the design pages under `docs/design/`.
- Update the [roadmap](docs/roadmap.md) if your change finishes or adds an item.

## Never commit secrets

Do not commit credentials, tokens, certificates or private keys. This includes:

- ChatGPT sessions (`chatgpt-session.json`) and Nextcloud app passwords;
- the dictation server's `token`, `tls/key.pem` and `tls/cert.pem`;
- keystores and signing configuration (`*.jks`, `*.keystore`, `keystore.properties`, `secrets.properties`);
- your own CA certificate, and addresses or host names of your private network.

`.gitignore` already excludes signing material and local build files. Use the placeholders from the documentation
(`nextcloud.example.com`, `192.0.2.10`, `alice`) in examples, tests and issues. Check `git status` and `git diff
--staged` before you commit.

If you find that a secret was committed, do not just delete it in a new commit. Report it as described in
[Security](SECURITY.md) so that it can be revoked.

## Commit messages

Look at `git log --oneline` for the style. Each message:

- starts with the area, then a colon, then the change, for example `Ink: partial redraw after undo and redo`,
  `Sync: no state rewrite when nothing changed` or `Fix fills: refilling inside an older fill trims it`;
- uses the imperative mood ("Add", "Fix", not "Added" or "Fixes");
- has a subject line of about 70 characters or fewer, and a body that explains **why** when the reason is not
  obvious.

## Report problems

Open an issue with the steps to reproduce, the Android version and the tablet model. For a security problem, do not
open a public issue. Follow [Security](SECURITY.md) instead.
