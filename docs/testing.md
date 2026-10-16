# Testing

Libreta has two kinds of checks: automated tests that run on a desktop JVM, and a manual checklist that you run on
a real tablet. The JVM tests cover the logic. Only a real tablet shows the pen, the touch screen, Android 4.4's TLS
stack and the memory limits.

## Run the automated checks

You need the toolchain described in [Build and install](setup/build.md).

1. Run the unit tests of both modules:

   ```bash
   ./gradlew :core:test :app:testDebugUnitTest
   ```

2. Run Android lint on the app. Lint aborts the build on errors, including `NewApi` calls above API 19:

   ```bash
   ./gradlew :app:lintDebug
   ```

3. Check `:core` against the Android 4.4 API:

   ```bash
   ./gradlew :core:animalsnifferMain
   ```

   You rarely need to run this by hand. The `:core` jar depends on it, so any build of the app runs it. Animal
   Sniffer fails the build if `:core` calls a class or method that API 19 lacks, such as `java.time` or
   `Map.getOrDefault`. Android lint cannot do this for a plain JVM module.

Tests never need a tablet, a Nextcloud server or a network. The tests that exercise HTTP use `MockWebServer`
from OkHttp, and the TLS tests create certificates on the fly. A test that waits on the network fails after three
minutes instead of hanging the build.

## What the automated tests cover

### `:core` (`core/src/test`)

| Area | Test classes | What they check |
|---|---|---|
| Sync | `SyncPlannerTest`, `SyncEngineTest` | The decision for each combination of local, remote and recorded state; conflict copies; deletions; a failed note that retries without stopping the rest |
| Notes | `NoteServiceTest`, `NoteRepositoryTest`, `NotePathTest`, `NoteNamingTest`, `NoteAttachmentsTest`, `ConflictNamerTest`, `ContentHashTest` | Creating, moving and deleting notes; path and title rules; attachment folders; conflict-copy names; content hashes |
| Search and index | `NoteSearchTest`, `NotebookIndexTest`, `NotebookIndexCacheTest`, `NoteTagsTest` | Case- and accent-insensitive search, snippets, the shared index and its cache (only changed notes are read again), tags in text notes |
| Links | `NoteLinksTest` | `[[…]]` with a category or a label; links in code are text; rewriting as Markdown links; resolving by title, category and the linking note's category; backlinks |
| Ink model | `InkTest` (strokes and `InkEditor`), `InkPrefilterTest`, `InkDiffTest`, `InkFillTest`, `InkSizesTest`, `InkViewportTest` | Erasing and cutting strokes; undo and redo; selection, move, scale, recolor, copy and paste as one undo step each; bounding-box prefilters give the same result as the full test; which regions change between two pages (partial redraw); paint-bucket fills; pen and eraser sizes; zoom, pan and fit-to-width maths |
| Ink files | `InkJsonCodecTest`, `InkNoteServiceTest`, `InkRectTest` | The `libreta.ink.json` codec (versions 1 and 2, marks); saving and reading handwritten notes; selection rectangles |
| Transcripts | `InkTranscriptsTest`, `InkTranscriptLedgerTest` | Which pages are pending; fingerprints; the ledger that skips complete notes |
| AI (core) | `ConversationNotesTest` | A saved conversation note |

### `:app` (`app/src/test`)

| Area | Test classes | What they check |
|---|---|---|
| Sync over the network | `NextcloudWebDavSyncTest`, `SyncIntegrationTest`, `NotesAppSettingsTest` | WebDAV requests and ETags against a fake Nextcloud; the real adapters together (files on disk, WebDAV, state file); the Notes app folder setting |
| TLS | `TlsTest`, `NextcloudCaStoreTest` | Trust of an imported private CA only, the system roots without one, and a new import taking effect; the certificate-name verifier; certificates for another name or from another CA are refused; importing, naming and removing the CA |
| Local storage | `FileNoteRepositoryTest`, `FileSyncStateStoreTest` | Notes and sync state on disk, atomic writes |
| ChatGPT sign-in | `ChatGptAuthTest`, `ChatGptSessionStoreTest` | Token refresh and rotation, `invalid_grant`, revocation, session storage and its cache |
| Responses API | `ResponsesClientTest`, `AiAssistantTest` | Streaming, a retry after 401, usage limit in the middle of a stream, HTTP errors, a cut stream; only the first message carries the image; the whole conversation is resent; a failure leaves no half message; the tool loop (call, result, denied permission); web searches announced and sources added; a math task shows its label, sends its request with `high` reasoning; every chat asks for LaTeX |
| Dictation | `SttClientTest` | A real HTTPS server with a self-signed certificate: token, `401`, a different certificate is refused, not paired, WAV header |
| YouTube | `YouTubeTest` | Video ids from every address form, and what is not a video; title and channel from oEmbed; a private video; a server error; the thumbnail address |
| Formulas | `InlineMathTest` | `$x$` with single dollars: where a formula starts and ends, prices stay text, `$$` is left to Markwon, escaped dollars, no formula across paragraphs |
| Threads | `IdleExecutorTest` | Tasks run in order on one named thread, and the thread ends when idle |

`AiTesting.kt`, `FakeNextcloud.kt` and `core/.../testing/Fakes.kt` hold the shared test helpers.

The tests do not cover the screens, the pen, the renderer on a canvas or the recording. Use the checklist below for
those.

## Manual checklist on a tablet

Run these checks on a real tablet after a change that touches the UI, ink, sync, TLS or the AI. Use a throwaway
category such as `Test` and delete it afterwards. Some checks need your own services: a Nextcloud server for sync, a
ChatGPT session for the AI and a paired dictation server for voice. Skip the sections that do not apply.

### Sync

1. Open «Ajustes» (Settings), enter the server address, user and app password, and select «Probar conexión» (Test
   connection). Check that it reports success and fills in the Notes app folder.
2. Create a text note in the new category. Check that the next sync uploads it and that the Nextcloud Notes app shows
   the same title, category and content.
3. Sync again without changes. Check that nothing is uploaded or downloaded.
4. Edit the note on the server and create a second note there. Sync. Check that the tablet shows the edit and the new
   note.
5. Edit the same note on the tablet and on the server, then sync. Check that both sides keep the server's text in the
   original and the tablet's text in a "(conflicto …)" copy.
6. Delete one note on the tablet and another on the server, then press Home. Check that both deletions reach the other
   side when the app closes.
7. Move a note to a subcategory from the editor. Check that the server shows it in the subfolder.
8. Lock a note in Nextcloud Notes and sync. Check that the rest uploads and the app says one note will retry.

### Text notes

1. Open a note. Check that it opens formatted (lists, bold, italic, strikethrough) and that «Editar» (Edit) shows the
   Markdown with highlighted symbols.
2. Create a new note. Check that it opens in the editor.
3. Search for a word with and without accents and capitals. Check the results and the empty-result message.
4. Write `$x^2 + y^2 = r^2$`, a `$$` block and `5 $ y 10 $` in a note. Check that the formatted view draws both
   formulas, that `+` and `=` are legible, and that the prices stay text.
5. Write `[[Destino]]` and `` `[[código]]` `` in a note. Check that only the first is a link, that tapping it offers
   to create «Destino», that the new note opens in the editor, and that its formatted view lists the first note under
   «Enlazada desde». Tap that entry and check that it opens. Long-press text and check that you can still select it.

### Handwriting

1. Draw with the pen in several colors and sizes on ruled paper, and add a second page. Check that the note appears
   in the list as handwritten and that Nextcloud receives the Markdown file, the strokes file and one image per page.
   Reopen the note and check that pages and colors come back.
2. Use the eraser in both modes. Check that "where I touch" cuts the line where the pen passes and that one undo
   restores it, and that "whole stroke" removes the entire line.
3. Draw, wait 4 seconds and force-stop the app. Check that the stroke is there on reopening.
4. Edit the note in Nextcloud Notes so that the strokes reference line disappears. Check that the app still
   recognizes it as handwritten.
5. Rest your palm on the screen while writing. Check that no stray strokes appear.

### Selection and editing

1. With the Select tool, mark the first letters of a word. Check that the box snaps to the strokes and a handle
   appears.
2. Move the selection, scale it with the handle, recolor it, change its thickness, copy and paste it (also on a new
   page), and delete it. Check that each operation is one undo step and that enough undos return the original note.

### Zoom, orientation and fill

1. Zoom with the − and + buttons and with a two-finger pinch, and pan with two fingers. Check that no ghost strokes
   appear, that the 1× button returns to the whole page, and that writing, erasing and selecting land under the pen at
   every zoom.
2. Rest a second finger on the screen while a stroke is in progress. Check that the stroke continues and the page does
   not zoom.
3. Write at 2.5×. Check that the text is proportionate at 1×.
4. Rotate the tablet with a handwritten note open. Check that strokes, undo, tool, selection and zoom survive, and
   that a portrait page is fitted to the width in landscape.
5. In landscape, add a page. Check that the app offers portrait or landscape and that the landscape page fits whole.
6. With the paint bucket, tap inside a circle that has a cross inside, and inside a circle with a small gap. Check that
   the fill reaches the outline, does not leak and leaves the cross visible. Tap again with another color, then
   undo. Check that the color changes and that undo restores the previous one.
7. Fill a circle drawn inside an already filled circle. Check that the outer fill keeps its shape and has no white
   line next to the ink.
8. Set the pen and eraser sizes with the sliders at their extremes, and change the thickness of a selection. Check the
   preview swatch and the number follow the slider.

### AI (needs a ChatGPT session)

1. Run `scripts/chatgpt-login.sh`. Check that the tablet shows the account in «Ajustes» and that the pushed file is
   gone from `/data/local/tmp`.
2. Write a word, select it, and choose «A texto» (To text). Check that the text appears in an editable box and that
   "Add to note" puts it above the page image.
3. Choose «Chat IA» (AI chat). Check that the answer streams, that a second question uses the first, that «Detener»
   (Stop) returns the question to the input field, and that an empty first message asks the model to explain the
   selection.
4. Save the conversation as a note. Check that it syncs with the selection image.
5. Write `1+1=1`, select it, and choose «Mates» > «Comprobar mis cálculos». Check that the chat sends «Comprueba mis
   cálculos.» on its own, that the answer starts with the formula it read, drawn rather than as LaTeX, and that it
   points out the error. Choose «Resolver» on an equation and check the steps and the result in bold.
6. Mark a title and a tag. Check that the AI proposes the text, that the index and the tag filter show them, and
   that the Markdown has the `## Índice` block and the `Etiquetas:` line.
7. Open the note menu and choose «Pasar a limpio» (Clean copy) on a note with two pages and a drawing. Check the progress
   messages, that the new note is organised in sections with a summary in italics, that the drawing appears as a
   picture of its strokes, without cut words, next to a sentence about it, that the note is in the same category and
   ends with the source line, and that the handwritten note did not change.
8. Write «Hola que tal», select it and choose «A texto» > «Pasar a letra». Check that typed text replaces the
   handwriting, in its place and colour, about as large as it was, and stays selected. Move it, resize it, recolour
   it, undo and redo. Choose «A texto» > «Corregir el texto», make it longer, and check that it stays on the page.
   Erase it with each eraser mode, and fill around it. Leave the note and open it again.
   Dictate a long sentence in the note, choose «En la página» and tap near the right edge: check that the text starts
   at the tap, breaks into lines at the edge and comes selected. Select a handwritten word and choose «A texto» >
   «Traducir…» > «Inglés»: check that the translation appears under it and the handwriting stays. Translate again and
   check that «Inglés» is now first.
9. In the general chat, ask about a text note and a handwritten note. Check the gray tool lines, the note links (the link
   opens the note, on the right page for handwriting), and the permission dialog for creating a note. Decline once
   and check that nothing is written.
10. Ask for something current. Check the "searching the web" line, the source links, the address dialog when you tap a
    link, and that a second question works after the search.
11. Select «Cerrar sesión» (Sign out). Check that the account disappears and that AI actions ask you to sign in.
12. Tap a link to a YouTube video in a chat answer and in a note. Check the card: the QR code at once, then the
    thumbnail, title and channel; scan the code with a phone; «Copiar enlace»; and, offline, «Sin conexión con YouTube.».

### Search over handwriting

1. Write a word in a new handwritten note and leave it. Check that searching for the word soon after finds it with
   the page.
2. Add a second page with another word. Check that only that page is transcribed and that the result opens the note
   on page 2.
3. Search for a word that does not exist. Check the empty-result message.
4. Turn off «Transcribir lo escrito a mano para buscarlo» and check that the pending count stops changing.

### Dictation (needs a paired server)

1. Pair with `scripts/stt-pair.sh`. Check that «Probar dictado» (Test dictation) reports the model.
2. Dictate a sentence from the chat, the text editor and a handwritten note. Check that the text arrives in the input
   field, at the cursor and in the editable box.
3. Cancel a recording and check that nothing is sent.

### Memory and TLS

1. Open a note with several pages at high zoom, and run the chat. Check that the app does not run out of memory.
2. Check that the connection to Nextcloud works over TLS 1.2 with your CA, and that a certificate from another CA is
   refused.
