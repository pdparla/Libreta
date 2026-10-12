# AI design

This document explains how Libreta talks to an AI model, how it reads handwriting for search, and how dictation
works, and why each piece is built the way it is. For how to use these features, see the
[assistant guide](../guide/assistant.md) and the [dictation guide](../guide/dictation.md).

## Overview

| Piece | Where it runs | What it uses |
|---|---|---|
| Conversations, "convert to text", clean copy, title and tag proposals, background transcription | In the app, in Kotlin | OpenAI's Responses API, paid with your own ChatGPT plan |
| Dictation | On a server you run | A `faster-whisper` container (`server/stt`) |

The app talks to `api.openai.com` directly. No Libreta server sits in between.

## Design decisions

| Topic | Decision | Why |
|---|---|---|
| Where the agent runs | In the app | The AI must not depend on a server you have to keep running. Node-based agents (Codex CLI, pi, the Agents SDK) do not run on Android 4.4. |
| How it is paid | Sign in with ChatGPT, using your plan's usage, through the [official flow for open-source and personal projects that run locally](https://developers.openai.com/siwc/token-sharing-open-source) | It is the documented route. The app does not use `chatgpt.com/backend-api` or the client ID of Codex, which are internal to Codex. |
| Signing in | In a browser on your computer, with `scripts/chatgpt-login.sh`, which copies the session to the tablet over adb | The tablet's browser cannot complete the sign-in page. The documentation covers this case: sign in on another machine and hand the credentials over a secure channel. |
| Conversations | In memory only. "Save as note" writes a Markdown note | There is no history to manage. What matters is saved as a note and synced like any other. |
| Reasoning effort | Medium for every request | A middle setting for the time and plan usage each request takes. |

## Sign in with ChatGPT

### Why a PC-side login

`scripts/chatgpt-login.sh` runs `scripts/ChatGptLogin.java` (Java 11 or later, no dependencies) on your computer.
The browser returns to `127.0.0.1` on the machine where Java runs. If you use WSL, run the Windows `java.exe` and
`adb.exe` (set `JAVA` and `ADB`). For the commands, see [Sign in with ChatGPT](../setup/chatgpt.md).

The script follows these steps:

1. **Host identity.** The first time, it creates an `ext_agent_host_id` (`urn:uuid:…`) for the tablet. It saves the
   identity and the issued `client_id` in `~/.libreta/chatgpt-client.json` on your computer. Neither value is a
   secret. Reusing them lets later sign-ins share the same registration, as the documentation asks.
2. **Authorization.** It opens `https://auth.openai.com/api/accounts/authorize` in your browser with:
   - `client_id=dynamic_agent_client` and `agent_name_hint=Libreta` the first time, and the issued `client_id` after
     that;
   - `scope=openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`;
   - `resource=https://api.openai.com/v1`, PKCE with S256, and a fresh `state` and `nonce` on every attempt;
   - `redirect_uri=http://127.0.0.1:1455/auth/callback`, with the callback server already listening.

   If the browser cannot reach that port (for example, Java in WSL and a browser in Windows), you can paste the
   address the browser ended up at into the terminal.
3. **Checks.** It verifies `state`; the `client_id` of the callback (required for a new registration, and equal to the
   stored one otherwise); the `nonce`, issuer, audience and expiry of the ID token, whose RS256 signature it
   verifies against `https://auth.openai.com/.well-known/jwks.json`; and that `chatgpt.tokens.use.direct` was granted.
4. **Hand-over.** It writes the session to a temporary file, pushes it to `/data/local/tmp/libreta-chatgpt.json`,
   starts `AdbImportActivity` on the tablet, waits for the confirmation in logcat, and deletes both copies.

### Session storage

The session holds `client_id`, `ext_agent_host_id`, `subject`, `email`, `access_token`, `refresh_token`, `id_token`,
`scope` and the expiry time. On the tablet it lives in `files/chatgpt-session.json` in the app's private storage, and
the app sets `allowBackup=false`. The tokens never reach logcat: `ChatGptSession.toString()` leaves them out, and
import errors name only the missing field.

The store refuses a session that lacks the `chatgpt.tokens.use.direct` scope, because an ID token alone cannot spend
your plan. It writes the file atomically and caches the parsed session until the file changes, because the main
thread asks whether you are signed in.

### Tokens

| Token | Lifetime | Use |
|---|---|---|
| Access | 1 hour | `Authorization: Bearer` against `api.openai.com` |
| Refresh | 30 days, and every refresh returns a new one | `POST https://auth.openai.com/api/accounts/oauth/token` with `grant_type=refresh_token`, the issued `client_id`, the refresh token and `resource` |

- The app refreshes when less than 5 minutes remain on the access token, so that it cannot expire during a long
  answer. It refreshes once more if the API answers 401.
- Refreshes run one at a time. The app writes the new pair of tokens atomically before using it, because the previous
  refresh token stops working as soon as a refresh succeeds.
- If a refresh fails with `invalid_grant`, the session has expired or was revoked. The app deletes it and asks you to
  run `scripts/chatgpt-login.sh` again. A session that you use at least once a month does not expire.
- **Sign out** in «Ajustes» (Settings) revokes the refresh token at
  `https://auth.openai.com/api/accounts/oauth/revoke` and deletes the local session. If the app cannot confirm the
  revocation, it deletes the session anyway and tells you that you can disconnect the app in your ChatGPT settings.

### TLS

The OpenAI hosts use public CAs that Android 4.4 does not know or that have changed since 2017, so the app bundles
the roots it needs. See [TLS and certificate trust](tls.md) for the roots, and why the OpenAI client and the
Nextcloud client never share trust.

## Responses API

Each turn is a `POST https://api.openai.com/v1/responses`:

```jsonc
{
  "model": "gpt-6.1-sol",
  "instructions": "…",
  "input": [ … the whole conversation … ],
  "reasoning": { "effort": "medium" },
  "include": ["reasoning.encrypted_content"],
  "store": false,
  "stream": true
}
```

Plan usage comes with [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
that shape the client:

- `store: false` and `stream: true` are mandatory. There is no `previous_response_id`, so **the app resends the whole
  conversation on every turn**. That includes the reasoning items the model returns, which arrive encrypted because of
  `include`, so that the model keeps its train of thought.
- There are no `system` messages: the instructions go in `instructions`. `temperature`, `max_output_tokens`,
  `metadata` and `truncation` are not accepted either.
- Text, images and function tools are supported. Audio and transcription are not, which is why dictation runs on your
  own server.
- The only success is the `response.completed` event. `response.failed` can arrive in the middle of a stream.

The client uses three stream events: `response.output_text.delta` (the text appears as it arrives),
`response.output_item.done` (the items it resends on the next turn) and `response.completed`.

Because the whole conversation is resent, the size of a turn grows with the chat. This is why tool results are kept
small (see [Notebook tools](#notebook-tools)) and why conversations are not stored.

### Models

`AiAssistant` defines two models, and both are `gpt-6.1-sol` with medium reasoning effort: `CHAT_MODEL` for
conversations and the clean copy, and `CONVERT_MODEL` for reading handwriting. A cheaper and faster model
(`gpt-6-luna`) was tried for reading handwriting and misread it too often, so reading uses the same model as
conversation.

### Language of the instructions

The instructions sent to the model are in Spanish and ask for answers in Spanish, like the app's interface. The
approximate location of the web-search tool is also set to Spain. Both are constants in `AiAssistant`.

### Errors

| Situation | Message the app shows |
|---|---|
| No session | "Sign in with ChatGPT from your PC: `scripts/chatgpt-login.sh`" |
| Expired or revoked session | The same, and the app deletes the local session |
| Plan usage limit (`subscription_sharing_usage_limit_exceeded`) | "You have reached your ChatGPT plan's usage limit" |
| Plan usage unavailable (`subscription_sharing_usage_unavailable`) | "Plan usage is not available" |
| No network | The same text as sync |
| Any other HTTP error | "OpenAI error (code)" |

The app shows these messages in Spanish.

## Convert to text

"Convert to text" sends one turn with a picture of the selection and an instruction to transcribe exactly what is
written as Markdown (lists, headings, line breaks) with no comments.

- The picture is rendered with the ink only, on white, without ruled lines or grid, at 2 pixels per unit and with the
  longer side limited to 1600 px. Paper lines add nothing for the model and can confuse it.
- The renderer adds a white margin around a selection (16 units, or 10% of the longer side). Without it, the strokes
  touch the edges of the image and the model reads nothing.
- Images are encoded as RGB 565 and rendered off the UI thread, to stay within the app's memory budget on a 1 GB
  device.
- The text appears in an editable box. "Add to note" inserts it above the page images, with the same
  precondition-checked merge as saving, so it never overwrites changes made in the meantime (`InkNoteService.addText`).
  The strokes are never touched, and the text is visible in Nextcloud Notes.

## Solve or check math

«Mates» opens the chat about the selection with a `MathTask`, and the chat sends its first message at once.

- **Two texts per message.** The conversation shows the task's short label («Resuélvelo.» or «Comprueba mis
  cálculos.»), and the model gets the task's request: read the mathematics and write it back in LaTeX first, so the
  user can see a misreading, then solve step by step or review every step and point to the first wrong one without
  redoing the exercise. `AiAssistant.send` takes the request separately (`request`), and `AiConversation` already
  keeps a shown text and a model text for each user message.
- **More reasoning.** The whole conversation uses `MATH_MODEL`, the chat model with `high` reasoning effort instead of
  `medium`. A wrong step or a missed error costs more than a slower answer.
- **Formulas everywhere.** Every chat, and "convert to text", asks for formulas in LaTeX with `$` inline and `$$` on
  their own line, which is what the formatted view draws (see
  [Formulas](architecture.md#formulas)). The instructions also forbid `\(` and `\[`, which Markwon does not read.
- **Selection.** «Mates» is enabled when the selection holds ink, like «Título» and «Etiqueta».

## Notebook tools

The general chat ("ask your notebook") and the chat about a selection use the same tools. The model searches, reads
and writes notes through function tools, and each call appears in the chat as a gray line.

| Tool | Parameters | Returns |
|---|---|---|
| `buscar_notas` | `consulta` | Up to 10 matching notes (using the same search as the note list): path, title, where it matched (title, text or page) and a snippet |
| `listar_notas` | `categoria` (optional) | Up to 50 notes, newest first: path, title, category, whether handwritten; and the existing categories |
| `leer_nota` | `nota` (path) | Title, category, text (for handwritten notes, your text without the managed lines) and what each handwritten page says; at most 6000 characters |
| `crear_nota` | `titulo`, `texto`, `categoria` (optional) | Path of the created note. **Asks permission** |
| `anadir_a_nota` | `nota`, `texto` | Confirmation. Appends text to a text note, or above the pages of a handwritten note. **Asks permission** |

The tool names have no accented letters or "ñ" because the API only accepts ASCII letters, digits, `_` and `-` in a
function name. The names stay in Spanish because the model reads Spanish notes and the interface is in Spanish.

Design points:

- **Tool loop.** `AiAssistant.send` requests a turn. If the model asks for tools, the app runs them, appends their
  results (`function_call_output`) to the conversation and asks again, until the model answers with text. The loop
  stops after 8 rounds, so that a model that never answers cannot loop forever. If anything fails, the app removes
  the user's message and everything the failed answer added, so you can send it again.
- **Small results.** `leer_nota` cuts at 6000 characters and `buscar_notas` returns snippets, so that the resent
  conversation stays small.
- **One index.** `NotebookIndex` (`:core`) builds what is searchable in each note. The note list and the tools use
  it, so they find exactly the same things.
- **Permission.** Tools run on the AI thread. The ones that write call `confirm(text)`, which shows a dialog on the
  main thread and waits for the answer. Cancelling the answer or closing the chat counts as "No". If you decline, the
  model is told and the conversation continues.
- **Citations.** The instructions ask the model to cite notes with links such as `[Title](<nota:Category/Title.md#2>)`.
  The angle brackets allow spaces in CommonMark, and `#2` is the page. Markwon resolves `nota:` links by opening the
  note. It opens no other link.
- **Gray tool lines.** They exist on screen only. The saved conversation note does not contain them.

## Web search

In both chats the model can search the web when a question needs it, such as current facts or something not in your
notes. It decides by itself.

- **OpenAI's own tool.** The app adds `{"type": "web_search"}` to the tools of the Responses API
  ([OpenAI guide](https://developers.openai.com/api/docs/guides/tools-web-search)), with an approximate location so
  that local searches make sense. OpenAI performs the search. The app needs no other service or key, and the tablet
  does not browse.
- **What comes back.** Each search is a `web_search_call` item with `action.query`, announced as soon as it arrives
  (`response.output_item.done`). The answer is a message with `url_citation` annotations. Citations that the model did
  not already link in the text are appended at the end as sources.
- **History.** Like everything else (`store: false`), the whole conversation is resent on each turn, including the
  `web_search_call` items. The API accepts them back with a plan session.
- **Opt-out.** `AiAssistant.send` has a `webSearch` parameter (on by default) for a chat without web access.
- **Plan usage.** A search spends plan usage like any turn. The instructions ask the model to search only when
  needed and to prefer your notes for anything that is yours.

Tapping a link in an answer shows the address with "Open" (in the tablet's browser, if it has one) and "Copy",
because the tablet may have no browser.

## Transcription for search

To find a handwritten note by what it says, the app transcribes every handwritten page in the background. The
transcription is stored with the note, and a search index reads it.

### Where the transcript lives

`.attachments.<id>/libreta.text.json`, next to the strokes:

```json
{"format":"libreta-text","version":1,"pages":[
  {"hash":"…","text":"Shopping list\n- bread\n- milk"},
  {"hash":"…","text":""}]}
```

- There is one entry per page, in order. `hash` is the page's fingerprint (its strokes, size and paper) at the time of
  transcription. If the page changes, the fingerprint no longer matches and the page is pending again. Marks (titles
  and tags) are not part of the fingerprint, so marking a title does not trigger a new transcription.
- The file sits in the attachments folder, so it syncs with Nextcloud, follows the note when you move or delete it, and
  another tablet does not transcribe the page again.
- It is written with the same precondition-checked merge as everything else (`WritePrecondition.MustMatch`), so a
  transcript saved in the meantime is not overwritten.
- The codec is handwritten in `:core`, like `InkJson`, because `org.json` does not exist in JVM tests.

### Which pages are transcribed

- `InkTranscripts` (`:core`) compares a note's pages with its `libreta.text.json` and returns the pending ones: new,
  changed and, if the note lost pages, it trims the extra entries.
- A page **without strokes** is saved with empty text and does not call the model.
- The model receives the whole page drawn with the ink only, on white, at the same 1600 px limit as a selection.
- This is what bounds plan usage: a new note costs one request per written page, and touching up a page costs one more.

### The background queue (`InkTranscriber`)

- It has its own thread. It walks the handwritten notes, transcribes pending pages **one at a time**, and saves each
  result immediately. If the app closes midway, finished work is kept and the rest stays pending. The thread ends
  when it is idle.
- Each pass makes one `list()` call and skips, without opening them, the notes that were already complete.
  `InkTranscriptLedger` (`:core`) remembers in memory, per note, the versions of its Markdown, strokes and
  transcript from when nothing was pending. If a version changes, or one of those files appears or disappears, the
  note is examined again. Checking that a file exists does not read the strokes. The pending-page count in
  «Ajustes» uses the same ledger.
- A pass starts when you leave a handwritten note (after it has saved) and when you open the note list. Two passes never
  overlap, and the queue never runs while you are writing.
- The queue stops silently if there is no ChatGPT session, no network, or the plan limit is reached. It retries on the
  next pass and never loops.
- When a pass transcribed something, the list refreshes so that search finds it.
- The setting «Transcribir lo escrito a mano para buscarlo» (Transcribe handwriting for search) turns the queue on or
  off. It is on by default.

### Search

- `NoteSearch` (`:core`) normalizes text to lowercase without accents (`java.text.Normalizer`) and looks for the query
  in the title, the text and the text of each page, in that order. It returns where it found the query and a snippet of
  about 60 characters. Snippets snap to whole words when there is a space within 15 characters.
- `NotebookIndex` builds the index when the list loads, in the background, and the list and the AI tools share it. It
  keeps each note's entry in memory together with the versions of what it read (the Markdown, the possible strokes and
  `libreta.text.json`, or their absence). With one `list()` of the repository, it re-reads only the notes whose
  versions changed and drops the ones that no longer exist. An in-memory index is enough for hundreds of notes.
- For handwritten notes, search skips the managed Markdown lines (images and the strokes reference). It searches the
  title, your text and the transcript.

## Titles and tags

With the Select tool, "Title" and "Tag" send the selection to the model like "convert to text" and propose the text in
an editable box. When you accept, the strokes are marked. A mark (`InkMark`) lives on one page and records its kind,
its text and the strokes it consists of, identified by their start time, which moving, scaling or recoloring does not
change. The mark's box is not stored: it is the box around its strokes, so it follows them. For the file format and
the Markdown, see [Note format](../reference/note-format.md).

## Clean copy

"Clean copy" in the handwritten note's menu turns a whole note into a new, structured text note. The handwritten
note does not change.

- **Reading the pages.** The app reuses the transcription. A page whose `libreta.text.json` entry is up to date (same
  fingerprint) is not sent again. The app reads missing pages now, one by one, with the "convert to text" model, and
  saves the readings in `libreta.text.json`, so search benefits too. Empty pages are skipped.
- **Ordering.** With one written page, its text is the result. With several, one more request joins them into a
  single Markdown: without repeats, without sentences cut between pages, with headings and lists where they exist and
  **without adding anything that is not written**. The titles you marked are passed as a guide to the structure.
  The instructions require keeping all the text and the marked titles, because a model asked only to tidy tends to
  drop content. They also forbid an overall title, which the app adds.
- **New note.** `NoteService.create` creates a text note titled "*Title* (clean copy)" in the same category (in
  Spanish, "(a limpio)"), numbered if the title exists. It syncs like any note. The note ends with a line saying which
  handwritten note it comes from and when.
- **Formulas.** The model writes formulas in LaTeX (`$…$`), which the formatted view and the chat draw. See
  [Formulas](architecture.md#formulas).
- **Errors.** If a request fails, the app shows the usual AI message and creates nothing. The pages read so far stay
  saved for next time. Cancelling creates nothing.

## Dictation service

Dictation records on the tablet and transcribes on a server you run. Deployment steps are in
[Dictation server](../setup/dictation-server.md). This section explains the design.

### Why a server

Plan usage does not include the transcription API, and the tablet's CPU cannot run Whisper. A server with a CPU and
no GPU can run `faster-whisper`.

### The client

A microphone button in the text editor, the handwritten note and the chat starts recording. "Stop" sends the recording
to the server and the text returns to the tablet.

- The app records 16 kHz, mono, 16-bit PCM WAV (`WavRecorder`), about 1.9 MB per minute, which is what Whisper works
  with. The recording stops by itself after 5 minutes, which is 9.6 MB. It goes to the app's cache directory and is
  deleted after sending or cancelling.
- The client waits up to 5 minutes for the answer, because a long recording takes a while on a server CPU.
- Where the text goes depends on the screen: into the text editor at the cursor; into an editable "dictated text" box in
  a handwritten note, with "Add to note" or "Copy"; into the input field in the chat, so you can review it before
  sending.

### The service (`server/stt`)

- **Engine.** `faster-whisper` on CPU with int8, the `large-v3-turbo` model, beam size 5, Spanish fixed, and a voice
  activity filter (VAD) so that silence is not transcribed. The service loads the model once and runs transcriptions one
  at a time, because each one already uses every thread it is given (`STT_THREADS`).
- **Why that model.** On a Spanish test recording, `small` and `medium` made visible mistakes (misheard names and
  words), and `large-v3-turbo` was the most faithful and faster than `medium`. A minute of dictation takes about a
  quarter of its length to transcribe on a modern multi-core CPU, and less when it has pauses, because the VAD removes
  them.
- **`app.py`** uses only the standard library plus `faster-whisper`:

  | Endpoint | Request | Response |
  |---|---|---|
  | `POST /v1/transcribe` | A WAV body (16 kHz, mono, 16-bit, which the service decodes with `wave` and NumPy) and `Authorization: Bearer <token>` | `{"text", "duration", "took"}`. `401` for a bad token, `400` for audio it cannot read, `413` for a body over 12 MB |
  | `GET /health` | None; needs no token | `{"ok": true, "model": "…"}` |

  The 12 MB limit leaves room above the 9.6 MB of a five-minute recording. The log has only durations and lengths,
  never what was said. The service compares the token in constant time and times out a client that stops sending.
- **Docker.** The service runs in a container so that it does not touch the server's Python or other services. A
  rootless Docker works: the container's root is then your server user, so the key and the token can stay at mode `600`.
  The `Dockerfile` pins two versions: `faster-whisper` 1.1.1 imports `requests` without declaring it, and PyAV 14
  removed an argument it uses, so it pins `av==12.3.0`.

### Own certificate and token

- **TLS.** `server/stt/setup.sh` creates a self-signed ECDSA P-256 certificate (`CN=libreta-stt`, with the server's IP
  address and an optional DNS name in the subject alternative names, valid for 10 years). The service accepts only
  TLS 1.2 or later with ECDHE and AES-GCM or ChaCha20, which is what Conscrypt supports on the tablet. The private key
  never leaves the server.
- **Pinned trust.** Pairing copies the public certificate to the tablet, and for dictation the app trusts **only** that
  certificate. Regenerating the certificate needs no new build: pair again.
- **Token.** The token is 256 random bits, created by `setup.sh` and handed to the tablet by `scripts/stt-pair.sh`
  through `AdbImportActivity` into `files/stt.json`.
- **Why not reuse the Nextcloud password.** The dictation service is a separate service with its own risk. A leaked
  dictation token can only transcribe audio, and you revoke it by deleting the token file and pairing again. The
  Nextcloud app password gives access to all your notes, so it should not travel to a second service or sit in its
  configuration.

"Test dictation" in «Ajustes» calls `/health`, which checks the address, TLS and certificate (not the token).

## Related documents

- [TLS and certificate trust](tls.md)
- [Architecture](architecture.md)
- [Scripts reference](../reference/scripts.md)
- [Security policy](../../SECURITY.md)
