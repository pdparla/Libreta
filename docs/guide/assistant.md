# The assistant

Libreta has an AI assistant that works on your notes. It can:

- answer questions about all your notes ([Ask your notebook](#ask-your-notebook));
- turn a whole handwritten note into a clean text note ([Make a clean copy of a note](#make-a-clean-copy-of-a-note));
- talk about a part of a handwritten page ([Chat about a selection](#chat-about-a-selection));
- search the web when a question needs it ([Web search](#web-search)).

The assistant talks to OpenAI directly from the tablet. It uses your ChatGPT subscription, so there is no extra key or server to set up.

## Requirements

- You must be signed in with ChatGPT. Sign-in is done once, from a computer connected to the tablet. See [Sign in with ChatGPT](../setup/chatgpt.md).
- The tablet needs a network connection.

Check the state in «Ajustes» (Settings), in the «IA (ChatGPT)» section. See [Settings](../reference/settings.md#ai-chatgpt).

If you are not signed in, the app shows the message «Inicia sesión con ChatGPT» (Sign in with ChatGPT) with the command to run on the computer.

Every request uses part of your ChatGPT plan, like any ChatGPT turn. If you reach the limit, the assistant says «has alcanzado el límite de uso de tu plan de ChatGPT» (you have reached the usage limit of your ChatGPT plan).

The assistant sends to OpenAI what it needs for your request: the picture of your selection, the text of your messages and, when it reads notes, the parts of the notes it looks at.

## Ask your notebook

1. In the note list, tap the speech bubble («Preguntar a la libreta», Ask your notebook).
2. Type a question in the box that says «Pregunta sobre tus notas…» (Ask about your notes…), then tap «Enviar» (Send). You can also tap the microphone to dictate the question. See [Voice dictation](dictation.md).
3. The answer appears as it is written. While it arrives, «Enviar» becomes «Detener» (Stop). Tap it to cut the answer. Your question goes back into the box.

Examples: «What did I write about the budget in September?» or «Make a list of what is pending this week and save it.»

### What the assistant does with your notes

The assistant has five tools for your notes. A grey line in the chat shows each one it uses, for example «Buscando «presupuesto»…» (Searching «budget»…) or «Leyendo «Compra»…» (Reading «Compra»…).

| Tool | What it does |
|---|---|
| Search notes | Finds notes that contain some words, also in handwriting. Up to 10 results. |
| List notes | Lists the 50 most recent notes, optionally of one category, and the categories that exist. |
| Read a note | Reads the text of a note and, for a handwritten note, what each page says. It reads up to 6000 characters. |
| Create a note | Creates a text note. Asks your permission. |
| Add to a note | Adds text to the end of a text note, or above the pages of a handwritten note. Asks your permission. |

The assistant cites the notes it uses as links. Tap a link to open the note. For a handwritten note, it opens on the page cited. If the note no longer exists, a message says «Esa nota ya no existe.» (That note no longer exists.)

### Permission to write

Before the assistant creates a note or adds text, a dialog titled «¿Permitir?» (Allow?) says what it wants to do and shows the start of the text. For example: «La IA quiere crear la nota «Pendientes» en «Casa».» (The AI wants to create the note «Pendientes» in «Casa».)

- Tap «Permitir» (Allow) to let it write.
- Tap «No» to refuse. The assistant is told and the conversation continues. Nothing is written.

Stopping the answer or leaving the chat also counts as «No».

The assistant never edits or deletes existing text. It only creates notes and adds text.

## Make a clean copy of a note

«Pasar a limpio» turns a whole handwritten note into a readable text note. The handwritten note does not change.

1. Open the handwritten note.
2. Open the menu and tap «Pasar a limpio».
3. Wait. A progress message shows «Leyendo la página 2 de 3…» (Reading page 2 of 3…) and then «Ordenando el texto…» (Putting the text in order…). Press the back button to cancel; nothing is created.
4. The new note opens, formatted.

The new note:

- is called «Title (a limpio)» and is in the same category as the original;
- contains everything written, as Markdown with headings, lists and tables where the original has them. The assistant joins the pages without repeating text or cutting sentences, and it adds nothing that is not written;
- uses the titles you marked as headings. See [Titles, tags and the index](organize.md);
- writes formulas as LaTeX between `$` signs, which the formatted view draws (see [Formulas](notes.md#formulas));
- ends with a line that says which handwritten note it comes from and when;
- syncs with Nextcloud like any other text note.

Pages that Libreta already read for search are not sent again. Empty pages are skipped. If nothing is written, a message says «No hay nada escrito en esta nota.» (There is nothing written in this note.)

If a request fails, you see the error and nothing is created. The pages already read are kept for next time.

## Chat about a selection

1. In a handwritten note, select the «Seleccionar» tool and draw a box around some handwriting or a drawing.
2. Tap «Chat IA» (AI chat).
3. The chat opens, titled «Conversar con IA» (Talk to the AI), with a small picture of your selection at the top.
4. Type your question and tap «Enviar». If you send an empty first message, the assistant explains what is in the picture.

The picture is sent with your first message only, and the assistant has the whole conversation in every turn. The assistant answers in Spanish, briefly, with formatted Markdown. If it cannot read something, it says so.

In this chat the assistant can also use the notebook tools and search the web.

## Solve or check math

The assistant can work on mathematics you wrote by hand: arithmetic, algebra, equations, derivatives and so on.

1. In a handwritten note, select the formula or the steps with the «Seleccionar» tool.
2. Tap «Mates» (Math) and choose one option:
   - «Resolver» (Solve): the assistant solves it step by step, with one sentence per step, and ends with the result
     in bold.
   - «Comprobar mis cálculos» (Check my working): the assistant reviews your steps. If they are right, it says so. If
     not, it points to the first wrong step, explains why, and writes the correct step, without redoing the whole
     exercise.
3. The chat about the selection opens and sends the request at once. Your message reads «Resuélvelo.» (Solve it.) or
   «Comprueba mis cálculos.» (Check my working.).

Either way, the answer starts with what the assistant read, as a formula, so that you can see whether it understood
your handwriting. If it misread something, say so in the chat and it corrects itself. You can keep asking in the same
chat, and save it as a note like any other.

These chats take a little longer, because the assistant thinks harder about mathematics.

Formulas in every chat are drawn, not shown as LaTeX. See [Formulas](notes.md#formulas).

## Save a chat as a note

Tap «Guardar como nota» (Save as note) in the top bar of the chat.

Libreta creates a note called «Conversación IA 2026-10-11 21.40» (the date and time). It contains:

- the picture of the selection, if the chat started from one, saved in an attachments folder as `recorte.png`;
- your messages, each starting with **Tú:**;
- the answers, each starting with **IA:**.

The note is in the category of the handwritten note, or in the category selected in the list when you started the chat. It syncs like any other note. The grey tool lines are not saved. Links to web pages are saved with the answer.

If the conversation is empty, a message says «Todavía no hay conversación.» (There is no conversation yet.) You cannot save while an answer is still arriving.

A conversation exists only in memory. If you leave the chat without saving it, it is lost. Rotating the tablet does not lose it.

## Web search

In both kinds of chat, the assistant searches the web when your question needs current information, or information that is not in your notes or in the picture. It decides by itself. You do not have to turn it on.

- A grey line shows the search, for example «Buscando en la web «weather Madrid»…».
- The answer cites the pages it used as links. Tap a link to see the address in a box titled «Página citada» (Cited page). Tap «Abrir» (Open) to open it in the tablet's browser, or «Copiar» (Copy) to copy the address. If the tablet has no browser, a message says so.
- A search uses your ChatGPT plan, like any turn. The assistant prefers your notes for anything about you.

## Errors

| Message | Meaning |
|---|---|
| «Inicia sesión con ChatGPT» | You are not signed in. |
| «la sesión de ChatGPT ha caducado; vuelve a iniciarla…» | The session expired. A session expires if you do not use the assistant for about a month. Sign in again. |
| «has alcanzado el límite de uso de tu plan de ChatGPT» | You reached your plan's limit. |
| «tu plan de ChatGPT no permite usarlo ahora desde Libreta» | Your plan does not allow this use now. |
| «error de OpenAI (HTTP 500)» | OpenAI returned an error. The code can vary. |
| «no se puede conectar con el servidor» | There is no network. |

Messages appear after «No se pudo:» (It could not be done:). When a chat answer fails, your message goes back into the box so you can send it again.

## Related

- [Handwritten notes](handwriting.md)
- [Titles, tags and the index](organize.md)
- [Voice dictation](dictation.md)
- [AI design](../design/ai.md)
