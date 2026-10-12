# Libreta documentation

This folder documents Libreta, an offline Markdown and handwriting notebook for Android 4.4 that syncs with
Nextcloud Notes. For an overview and a quick start, see the [project README](../README.md).

## Guide

How to use the app.

| Document | Contents |
|---|---|
| [Text notes](guide/notes.md) | Text notes, categories, the editor and search. |
| [Handwriting](guide/handwriting.md) | The pen, selection, zoom, orientation, fill and stroke widths. |
| [Titles, tags and the index](guide/organize.md) | Mark titles and tags in handwriting and get an index. |
| [Assistant](guide/assistant.md) | Ask your notebook, clean copy, chat about a selection and web search. |
| [Voice dictation](guide/dictation.md) | Dictate into the editor, a handwritten note or the chat. |

## Setup

How to build the app and connect it to your services.

| Document | Contents |
|---|---|
| [Build and install](setup/build.md) | Toolchain, build, release signing and installation with `adb`. |
| [Connect to Nextcloud](setup/nextcloud.md) | App password, server address, notes folder, certificate name and CA import. |
| [Sign in with ChatGPT](setup/chatgpt.md) | Give the tablet access to your ChatGPT plan. |
| [Deploy the dictation server](setup/dictation-server.md) | Run `server/stt` and pair the tablet. |
| [Kiosk mode](setup/kiosk-mode.md) | Hide unneeded apps and use Libreta as the home screen. |

## Reference

| Document | Contents |
|---|---|
| [Settings](reference/settings.md) | Every setting in the app. |
| [Note format](reference/note-format.md) | How notes and handwritten notes are stored on Nextcloud, and attachments. |
| [Scripts](reference/scripts.md) | Every script, its options and environment variables. |
| [Compatibility](reference/compatibility.md) | Android 4.4 limits: Dalvik, TLS, Conscrypt and memory. |

## Design

Why the app works the way it does.

| Document | Contents |
|---|---|
| [Architecture](design/architecture.md) | Modules, layers and `AppContainer`. |
| [Sync](design/sync.md) | The sync engine, conflicts, ETags and WebDAV. |
| [TLS](design/tls.md) | CA trust, the certificate-name verifier and the OpenAI roots. |
| [Ink](design/ink.md) | The ink model, renderer, partial redraw, diff, fill, zoom and selection. |
| [AI](design/ai.md) | Sign in with ChatGPT, the Responses API, notebook tools, transcription, web search and the dictation service. |

## Testing and roadmap

| Document | Contents |
|---|---|
| [Testing](testing.md) | JVM tests and the manual test checklist for a tablet. |
| [Roadmap](roadmap.md) | What is still pending. |

See also [Contributing](../CONTRIBUTING.md) and [Security](../SECURITY.md).
