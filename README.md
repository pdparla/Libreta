# Libreta

Libreta is an offline notebook for old Android tablets. It keeps text notes in Markdown, lets you write and draw
by hand, and syncs with the Notes app of your own Nextcloud server over WebDAV. It runs on Android 4.4 (API 19),
so a tablet that no longer gets updates can still be a useful notebook.

https://github.com/user-attachments/assets/ce0b2972-2157-4eea-9ae0-f0371e548a88

The app was built for a Samsung Galaxy Tab 3 Lite (SM-T113). Its interface is in Spanish.

## Features

- **Text notes in Markdown.** A note opens formatted. You switch to the editor, which highlights the Markdown
  syntax, to change it. Notes save automatically.
- **Handwritten notes.** Write or draw with a finger or a stylus. The notebook has a pen with adjustable width and a
  color palette, an eraser, undo and redo, several pages, and ruled, grid or blank paper. You can select strokes
  to move, resize, recolor or copy them, fill shapes, and zoom from 1x to 4x. The tablet can rotate.
- **Nextcloud sync.** Every note is a `.md` file in your Nextcloud Notes folder, so it also shows in the Notes
  app. Handwritten notes appear there as one image per page, and the strokes sync too, so you can keep editing
  them on the tablet. When both sides change a note, Libreta keeps both versions.
- **Search.** One search box covers titles, text and handwriting.
- **Titles, tags and an index.** Mark a title or a tag in your handwriting, and the note gets an index. You can
  filter the list by tag.
- **Optional AI with your ChatGPT plan.** If you have a ChatGPT plan, you sign in with *Sign in with ChatGPT*
  and use it from the tablet. You can turn handwriting into text, make a clean copy of a handwritten note, chat
  about a selection, and ask questions about your whole notebook. The chat can search the web. Libreta has no
  server of its own for this: the tablet talks to OpenAI directly.
- **Optional voice dictation.** A microphone button in the editor, the handwritten note and the chat sends your
  voice to a dictation service that you host yourself, and puts the text back.
- **Single-app mode.** You can make Libreta the home screen and hide the apps you don't need.

## Requirements

- An Android device with Android 4.4 (API 19) or later and a 32-bit ARM processor (`armeabi-v7a`).
- A Nextcloud server with the Notes app, reachable over HTTPS. The server can use a private certificate authority
  (CA).
- A computer with a USB cable and `adb`. The build and the setup scripts run there.
- For the AI features: a ChatGPT plan that includes Sign in with ChatGPT.
- For dictation: a Linux server with Docker, about 4 GB of free RAM, and about 2 GB of disk space.

## Quick start

1. Build the app and install it on the tablet. See [Build and install](docs/setup/build.md).

   ```bash
   ./gradlew :core:test :app:assembleDebug :app:lintDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. On the tablet, open Libreta and go to «Ajustes» (Settings). Enter your Nextcloud address, user name and an
   app password, then tap «Probar conexión» (Test connection) and «Guardar» (Save). See
   [Connect to Nextcloud](docs/setup/nextcloud.md).
3. If your server uses a private CA, or a certificate that Android 4.4 doesn't know, import the CA certificate
   from your computer:

   ```bash
   scripts/nextcloud-ca.sh my-ca.pem
   ```

4. Optional: sign in with ChatGPT for the AI features with `scripts/chatgpt-login.sh`. See
   [Sign in with ChatGPT](docs/setup/chatgpt.md).
5. Optional: deploy the dictation service and pair the tablet. See
   [Deploy the dictation server](docs/setup/dictation-server.md).
6. Optional: hide the apps you don't need and use Libreta as the home screen. See
   [Kiosk mode](docs/setup/kiosk-mode.md).

## Documentation

The [documentation index](docs/README.md) lists every document. The main entry points are:

| If you want to | Read |
|---|---|
| Use the app | [Text notes](docs/guide/notes.md), [Handwriting](docs/guide/handwriting.md), [Assistant](docs/guide/assistant.md) |
| Build and set it up | [Build and install](docs/setup/build.md), [Connect to Nextcloud](docs/setup/nextcloud.md) |
| Look up a setting or a script | [Settings](docs/reference/settings.md), [Scripts](docs/reference/scripts.md) |
| Understand how it works | [Architecture](docs/design/architecture.md), [Sync](docs/design/sync.md) |
| Contribute | [Contributing](CONTRIBUTING.md), [Testing](docs/testing.md), [Roadmap](docs/roadmap.md) |

## Project layout

| Path | Contents |
|---|---|
| `core/` | Kotlin/JVM module with the note model and the sync engine. It has no Android code and is tested on the JVM. |
| `app/` | The Android app (`minSdk` 19): storage, WebDAV, TLS, AI, dictation and the user interface. |
| `scripts/` | Scripts that run on your computer: ChatGPT sign-in, dictation pairing, CA import and debloat. |
| `server/stt/` | The dictation service (faster-whisper in a Docker container). |
| `docs/` | Documentation. |

## License

Copyright 2026 David Parla.

Libreta is licensed under the [Apache License, Version 2.0](LICENSE).
