# Architecture

This document describes how Libreta is divided into modules and layers, how the object graph is built, which
threads do what, and how notes are stored and saved. Read it before you change anything that crosses a layer.

## Modules

| Module | Type | Contents |
|---|---|---|
| `:core` | Kotlin/JVM, no Android | The domain (`NotePath`, `NoteRepository`, `NoteService`), the sync engine, the ink model and its editing logic, search, and the conversation-to-note logic. Tested on the JVM. |
| `:app` | Android application (minSdk 19) | The composition root (`AppContainer`), the adapters, the sync orchestration, and the user interface built with classic Views. |

`:core` has no Android dependency, so its tests run in plain JVM tests and it can't call an API that doesn't exist
on the device by accident (see [Compatibility](../reference/compatibility.md#api-19-guards)).

### Why adapters live in `:app`

The adapters (files, WebDAV, TLS, ChatGPT, dictation, settings) sit in `:app`, not in extra JVM modules:

- They are plain Java and Kotlin, so their tests also run on the JVM (`testDebugUnitTest`).
- Because they are in `:app`, Android lint checks that they don't use an API newer than API 19. Lint's `NewApi`
  check doesn't run on JVM modules. `:core` gets the same protection from Animal Sniffer instead.
- One module fewer keeps the build and the method count small on a tablet this size.

The dependency rule is one-way: `:app` depends on `:core`, never the reverse. Ports are interfaces in `:core`
(`NoteRepository`, `SyncStrategy`, `SyncStateStore`, `PageRenderer`); `:app` implements them.

## Packages

### `:core` (`com.parla.libreta.core`)

| Package | Responsibility |
|---|---|
| `note` | Paths and names (`NotePath`, `NoteNaming`), the repository port and its optimistic-concurrency types (`NoteRepository`, `Version`, `WritePrecondition`), the editor's use cases (`NoteService`, `NoteDraft`), tags, attachments, search (`NoteSearch`) and the in-memory notebook index (`NotebookIndex`). |
| `sync` | The sync engine (`SyncEngine`), the pure planner (`SyncPlanner`), and the remote-side port (`SyncStrategy`). See [Sync](sync.md). |
| `ink` | The ink model (`Stroke`, `InkPage`, `InkDocument`), the editor with undo (`InkEditor`), the JSON codec, the note format and service, fill, selection geometry, the viewport, and the transcription ledger. See [Ink](ink.md). |
| `ai` | The conversation-to-note logic (`ConversationNotes`). The AI client itself is an adapter in `:app`; see [AI design](ai.md). |

### `:app` (`com.parla.libreta`)

| Package | Responsibility |
|---|---|
| (root) | `LibretaApp`, `AppContainer`. |
| `data.local` | `FileNoteRepository` (notes as files), `FileSyncStateStore`, and the atomic file write. |
| `data.remote` | WebDAV (`NextcloudWebDavSync`, `MultiStatusParser`, `ListingCache`), the client factory, the imported CA store, and TLS (`Tls`). See [TLS](tls.md). |
| `data.ai` | The ChatGPT session, the Responses API client, the notebook tools. |
| `data.stt` | The WAV recorder and the dictation client. |
| `data.settings` | The server settings. |
| `sync` | `SyncCoordinator` and `SyncService`, which run the engine. |
| `ui` | Activities, adapters, Markdown rendering, background runner. `ui.ink` holds the handwriting screen, view and renderer; `ui.ai` the chat; `ui.dictation` the dictation button. |

The app uses `android.app.Activity` and the system Holo theme. It has no AndroidX, AppCompat or Compose; the only
AndroidX artifact is `androidx.annotation`, which Markwon brings and which contains only annotations.

## Composition root

`AppContainer` builds the object graph once per process. `LibretaApp.onCreate` creates it, and activities reach it
through the `Activity.container` extension property. There is no dependency-injection framework.

Things to know when you add to it:

- Anything expensive is `lazy` and is first touched off the main thread. Loading Conscrypt's native library and
  parsing certificates take a moment.
- One `Conscrypt` provider serves all TLS clients. It's passed to each `SSLContext` explicitly and never installed
  as the process-wide default, so nothing else in the process changes.
- All HTTP clients derive from one cleartext-only base `OkHttpClient` with `newBuilder()`. They share its
  connection pool and dispatcher, and so its threads. OkHttp keys connections by address, including the socket
  factory, host-name verifier and connection specs, so a client never reuses a connection made under another
  trust configuration. The base is cleartext-only on purpose: with a TLS spec and no socket factory, OkHttp
  would load the system trust store just to build a factory that every derived client replaces.
- There are three TLS clients with three different trust configurations: Nextcloud, OpenAI and the dictation
  service. See [TLS](tls.md).
- The ChatGPT client is built lazily, so a tablet that never signs in never pays for it.

## Threading

The main thread draws and handles input. Everything slower runs on a small, fixed set of executors.

| Executor | Created by | Used for |
|---|---|---|
| `libreta-background` | `AppContainer.background` | General short work for screens, such as listing and deleting notes, and saving the account settings. |
| `libreta-ai` | `AppContainer.aiBackground` | Model calls. They take seconds, so they have their own thread and never hold up `libreta-background`. |
| `libreta-stt` | `AppContainer.sttBackground` | Dictation uploads and the dictation connection test. |
| `libreta-transcriber` | `InkTranscriber` | The background transcription pass for search. |
| `SyncService` worker | `IntentService` | Sync runs. `IntentService` serialises requests on its own thread. |
| Activity-owned | Editor activities | The text editor's Markdown highlighter, and the handwriting screen's autosave thread. |

`BackgroundRunner` is a small stand-in for coroutines, which this project doesn't use on API 19. It runs `work`
on an executor and posts the `Result` to the main thread. Its executors come from `idleSingleThreadExecutor`: a
single thread that runs tasks in submission order and ends after 30 seconds without work, then starts again on
demand. An idle screen therefore doesn't keep a thread and its stack alive for each executor.

Rules:

- Repository methods are safe to call from any thread. `FileNoteRepository` holds one lock, so checking a
  precondition and writing are atomic between the editors and the sync thread.
- Sync runs are serialised (`SyncCoordinator.syncNow` and `SyncEngine.sync` are `@Synchronized`), and
  `SyncService` collapses requests that pile up into one more run.
- Listeners (`SyncCoordinator`, `InkTranscriber`) are called on the main thread.
- Never log note names or note text. Log lines carry counts and exception types.

## Local storage

All app data is in the app's private storage, under `filesDir`. The app sets `allowBackup=false`.

| Path or store | Contents |
|---|---|
| `files/notes/` | The notes tree, in the same layout as the Nextcloud notes folder: one subfolder per category (`/` makes subcategories), notes as `.md` or `.txt`, and attachments in `.attachments.<id>/` folders next to their note. |
| `files/sync-state.tsv` | The last-synced versions, one note per line. |
| `files/chatgpt-session.json` | The ChatGPT sign-in session. |
| `files/stt.json` | The dictation service address, token and certificate. |
| `files/nextcloud-ca.pem` | The CA certificates you imported for Nextcloud, if any. |
| SharedPreferences | Server settings, including the app password, and other preferences such as the last pen settings and the transcription switch. |

The app password is stored in clear text inside the sandbox. The tablet isn't rooted, backups are off, and the
Android Keystore on API 19 has no symmetric keys and can be wiped by changes to the lock screen. The password is a
revocable Nextcloud app password, never the account password. If the server, user or notes folder changes, the
sync state is deleted (`SyncCoordinator.changeAccount`).

### Atomic writes

Every file the app writes goes through `writeAtomically`. It writes a hidden sibling named `.<name>.tmp`, flushes
it to disk with `fd.sync()`, and renames it over the target. `rename(2)` is atomic on ext4, so a reader, or a
power cut, sees either the old file or the new one, never a half-written one. The temporary file is deleted in a
`finally` block. The repository's listing skips hidden temporary files because they don't parse as a note or
attachment path.

### File versions

`FileNoteRepository` uses a content hash as a file's version. Hashing every page image on every sync would be
slow on this tablet, so it keeps a cache of hashes keyed by path and valid while the file's modification time and
size don't change. This is safe because every write goes through the class, which updates the entry, and nothing
else writes into the private folder. A sync with no local changes doesn't read the images again.

Deleting a file removes its folder if it was the last file in it, as Nextcloud Notes does with categories.

## Formulas

The formatted view and the chat draw LaTeX with Markwon's `ext-latex` module, which uses JLaTeXMath for Android. It
draws each formula as a drawable, so no WebView is needed: the KitKat WebView is too old for KaTeX or MathJax.
`ui/Markdown.kt` sets it up:

- **Two syntaxes.** Markwon's own parsers handle `$$` blocks and `$$x$$` inline. The model and "clean copy" write
  `$x$` with single dollars, so a second inline processor, `SingleDollarLatex`, handles those. `InlineMath` decides
  where such a formula starts and ends, with Pandoc's rule, so that prices stay text. It is plain Kotlin, tested on
  the JVM in `InlineMathTest`.
- **Off the main thread.** Formulas are laid out on one idle-timeout thread (`libreta-latex`) and appear when ready.
- **Size.** Formulas are drawn at 1.2 times the size of the text. Computer Modern looks smaller than the system
  font, and at 160 dpi a smaller `+` loses its horizontal bar.
- **Errors.** A formula that JLaTeXMath can't parse is shown as its source in grey.
- **The editor stays plain.** Markdown highlighting while you type uses a Markwon without formulas, so typing never
  waits for LaTeX.

## The editor's save model

### Text notes

The text editor has no save button. It saves when the activity stops being visible (`onPause`), when it goes back
to the formatted view, and in `onSaveInstanceState`.

The save runs on the main thread on purpose. A note is a few kilobytes, and a synchronous write in `onPause` is
the only way to be sure the text is on disk before Android can kill the process.

The editor holds a `NoteDraft`: the current text and category, the same values from when the note was opened, and
`origin`, the stored note it came from with its version. `NoteService.save` works like this:

1. If the draft is unchanged, or a new note has no text, there's nothing to save.
2. A new note is created at a free path named after its first line, in its category.
3. An existing note is written with `WritePrecondition.MustMatch(origin.version)`. The file name follows the first
   line of the text (the title), and the file lives in its category's folder.
4. If the title or category changed, the note moves. The new files are written before the old ones are deleted, so a
   failure never loses anything. Attachments follow the note only when its folder changes, because links are
   relative to it.

If the precondition fails, the note changed on disk while you edited it, usually because a sync brought a newer
version. The save never overwrites a version the editor hasn't seen:

- If the file still exists, the draft is stored next to it as a conflict copy, `Name (conflicto YYYY-MM-DD).md`,
  and the editor tells you.
- If the file was deleted meanwhile, the edit brings it back.

After a successful save the editor continues from the returned note, whose path may be new.

### Handwritten notes

A handwritten note saves on exit, when the screen turns off, and 3 seconds after you last touch the page if there
are changes. It doesn't save while you write, because saving renders the page, and you would feel it in the
stroke. The first save of a new note happens immediately; the note is created only when something is drawn.

Autosave runs on a separate thread. `InkDocument` is immutable, so the UI thread takes a snapshot and the saver
thread saves exactly that. At most one background save runs at a time. Every other save (leaving, renaming, moving,
AI actions, deleting) first waits for the running one to finish and takes its result, so saves never overlap and
none starts from a stale note or path. When a background save ends and there's anything left to save, it schedules
the next one.

`InkNoteService.save` writes files in an order that never leaves a note pointing at missing pages:

1. The strokes file, `libreta.ink.json`.
2. The PNG of each page that changed. Marks aren't drawn, so adding a title or tag doesn't render the page again.
3. The Markdown last. It's merged, not overwritten: the service reads the current text, replaces only the lines it
   manages (page images, index, tags and the strokes reference), and writes with `MustMatch`. If the Markdown
   changed between the read and the write, it reads again, up to three attempts. Text you typed or the app added
   from handwriting recognition is kept.
4. Images for pages that no longer exist are deleted.

The same read-merge-write loop stores the transcription file used for search.

See [Note format](../reference/note-format.md) for the files themselves and [Sync](sync.md) for how they reach
Nextcloud.
