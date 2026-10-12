# Text notes

A text note is a Markdown file. You write it on the tablet, and Libreta saves it and syncs it with the notes folder of your Nextcloud server. The same note then appears in the Nextcloud Notes app and in any other app that reads that folder.

For handwritten notes, see [Handwritten notes](handwriting.md). For the file layout, see [Note format](../reference/note-format.md).

## The note list

The list is the home screen. It shows your notes, the most recently changed first. Each row shows the title and a second line with the category and how long ago the note changed. Handwritten notes start that line with «A mano» (Handwritten).

The top bar has these controls:

| Control | What it does |
|---|---|
| Category drop-down (left) | Filters the list by category or tag. See [Categories](#categories) and [Titles, tags and the index](organize.md). |
| Magnifier («Buscar», Search) | Searches all notes. See [Search](#search). |
| Speech bubble («Preguntar a la libreta», Ask your notebook) | Opens a chat about your notes. See [The assistant](assistant.md). |
| ⊕ («Nueva nota», New note) | Creates a text note. |
| Pen icon («Nota a mano», Handwritten note) | Creates a handwritten note. |
| Sync icon («Sincronizar», Sync) | Syncs now. |
| Gear («Ajustes», Settings) | Opens the settings. See [Settings](../reference/settings.md). |

To delete a note from the list, touch and hold it, then tap «Borrar» (Delete).

If the list is empty, it shows «No hay notas todavía. Pulsa ⊕ para crear la primera.» (No notes yet. Tap ⊕ to create the first one.)

## Create and edit a note

1. Tap ⊕. A new note opens in the editor with the keyboard visible.
2. Type. The first line is the title of the note.
3. Tap the back arrow. The note is saved.

A note that has no text is not created.

### Formatted view and editor

A note opens in one of two modes. The button in the top bar switches between them:

- **Formatted view.** The note is shown as formatted text, with headings, lists, bold, tables and images. The top-bar button reads «Editar» (Edit).
- **Editor.** You see and edit the Markdown. Markdown symbols are grey, and bold, italic and headings are styled. The top-bar button reads «Ver» (View).

An existing note that has text opens in the formatted view. A new note, an empty note, and a note you open with «Editar el texto» (Edit the text) from a handwritten note open in the editor.

The formatted view also shows the images that a note links to. A handwritten note therefore shows its pages in this view.

#### Formulas

The formatted view and the chat draw LaTeX formulas:

| You write | You see |
|---|---|
| `$x^2 + y^2 = r^2$` or `$$x^2$$` inside a sentence | The formula in line with the text |
| `$$` on its own line, the formula, and `$$` on its own line again | The formula centered on its own line |

Prices are not formulas: in `5 $ y 10 $` and `$5 y $10`, the dollar signs stay as text. A single `$` opens a formula only when the next character is not a space, and closes it only when the previous character is not a space and the next one is not a digit. To write a literal dollar sign inside a formula, use `\$`.

A formula that LaTeX can't read is shown as its source in grey. In Nextcloud Notes, formulas show as their LaTeX text.

The top bar of the editor also has these buttons:

- The microphone dictates text at the cursor. See [Voice dictation](dictation.md).
- «Categoría» (Category) moves the note. See [Categories](#categories).
- The trash icon deletes the note after you confirm.

### Autosave

There is no save button. Libreta saves the note when you:

- leave the note, switch the screen off or switch to another app;
- tap «Ver» to go back to the formatted view;
- rotate the tablet.

If a save fails, the text stays in the editor and Libreta tries again the next time you leave the screen. A message says «No se pudo guardar la nota. Se volverá a intentar.» (The note could not be saved. It will be tried again.)

### The file name follows the first line

The title of a note is its first non-empty line. Libreta removes a leading `#` or list marker, so `# Shopping` gives the title `Shopping`. The note is stored in a file named after the title (`Shopping.md`).

When you change the first line, the file is renamed on the next save. Libreta also changes these characters, which are not safe in file names: `* | / \ : " < > ?` are removed, runs of spaces become one space, and a title longer than 100 characters is cut. A note with no title text is called «Nota nueva» (New note).

If another note already has the same title in the same category, Libreta adds a number: `Shopping (2).md`.

## Links between notes

Write the title of another note between double brackets to link to it, as in many notebook apps:

| You write | It links to |
|---|---|
| `[[Compra]]` | The note titled «Compra». Capitals don't matter. |
| `[[Casa/Compra]]` | The note «Compra» in the category «Casa». Use it when several notes share a title. |
| `[[/Compra]]` | The note «Compra» with no category. |
| `[[Compra\|la lista]]` | The note «Compra», shown as «la lista». |

In the formatted view, a link looks like any other link:

- Tap it to open the note. A handwritten note opens on its first page.
- If no note has that title, a dialog asks «No hay ninguna nota «…». ¿Crearla?» (There is no note «…». Create it?).
  Tap «Crear» (Create) to make the note, in the link's category or else in the current one, and to start writing it.
- If several notes share the title, the one in the same category as the current note wins. Otherwise, the first by
  category name does.

At the end of the formatted view, «Enlazada desde» (Linked from) lists the notes that link to this one. Tap one to open
it. The list appears a moment after the note, because Libreta checks every note the first time.

A link to a YouTube video shows a card with a QR code to watch it on your phone. See [YouTube videos](assistant.md#youtube-videos).

Links inside code (`` `[[…]]` `` or a code block) are text. In Nextcloud Notes, links show as `[[…]]` text. When the
assistant writes a note for you, it links other notes the same way. See [Ask your notebook](assistant.md#ask-your-notebook).

## Categories

A category is a folder. Use `/` for sub-categories, for example `Home/Kitchen`.

- To put a note in a category, open it and tap «Categoría». Choose «Sin categoría» (No category), an existing category, or «Nueva categoría…» (New category…) and type a name. Libreta moves the note on the next save.
- To filter the list, open the drop-down at the left of the top bar. It lists «Todas las notas» (All notes), «Sin categoría», every category, and then every tag. A sub-category has its own entry.
- A note you create while a category is selected in the drop-down is created in that category.

Libreta cleans the category name the same way as a note title.

## Search

Tap the magnifier and type. The list narrows as you type.

- Search covers the title and the text of every note, and the text of your handwriting. See [Search handwriting](#search-handwriting).
- Search ignores upper and lower case and accents. «medico» finds «médico».
- Each result shows a short snippet around the match. A match in handwriting also shows the page, for example `A mano · Casa · pág. 2 · …compraré pan y leche…`.
- Search respects the category or tag selected in the drop-down.
- When nothing matches, the list says «Ninguna nota contiene «…»» (No note contains «…»).
- Tap a result to open it. A result in handwriting opens the note on that page.

To leave the search, close the search field. The list shows all notes again.

### Search handwriting

Handwriting is not text, so Libreta reads it in the background and keeps the result next to the note. The reading is done page by page by the AI, with your ChatGPT sign-in.

- It starts when you leave a handwritten note and when you open the list. It never runs while you write.
- Only pages that changed are read again. Empty pages are not sent.
- It stops without any message if you are not signed in, there is no network, or you reached your plan's usage limit. The pages that are left are tried again on the next pass.
- The reading syncs with the note, so another tablet does not read it again.

You can turn this off, and see how many pages are waiting, in the settings. See «Transcribir lo escrito a mano para buscarlo» in [Settings](../reference/settings.md#ai-chatgpt). To sign in, see [Sign in with ChatGPT](../setup/chatgpt.md).

## Sync

Libreta works offline. Notes are stored on the tablet and synced with Nextcloud.

Sync starts when you:

- open the app;
- send the app to the background;
- tap the sync icon;
- save the settings.

A line under the top bar shows the state:

| Message | Meaning |
|---|---|
| «Sincronizando…» | A sync is running. |
| «Sincronizado a las 14:32» | The last sync finished at that time. |
| «Sincronizado a las 14:32; una nota se reintentará» | One note could not be synced and is tried again next time. A common cause is a note that is open in the Nextcloud web editor. |
| «Sin sincronizar: …» | The sync failed. The reason follows, for example wrong credentials, server unreachable, or a certificate problem. |
| «Sin sincronizar: configura el servidor en Ajustes.» | The server is not set up yet. See [Connect to Nextcloud](../setup/nextcloud.md). |

Rules that matter when you use more than one device:

- If you change a note on the tablet and on another device before a sync, neither version is lost. See [Conflict copies](#conflict-copies).
- If you edit a note on one side and delete it on the other, the edit wins and the note is kept.
- If the notes folder is missing on the server, the sync stops. Libreta never reads that as "all notes deleted".
- If the network is down, the sync stops without changing anything and continues later.

For the design behind these rules, see [Sync](../design/sync.md).

### Conflict copies

A conflict happens when the same note changed on the tablet and on Nextcloud since the last sync.

When Libreta finds one, it keeps both versions:

- The version from Nextcloud keeps the original name.
- Your version from the tablet is saved as a new note named `Name (conflicto 2026-10-11).md`, on the tablet and on Nextcloud. If that name exists, Libreta adds ` 2`, ` 3` and so on.

A message says «Una nota cambió en los dos sitios: se ha guardado una copia «(conflicto)»» (One note changed in both places: a copy named «(conflicto)» was saved). Open both notes, copy what you need, and delete the copy you no longer want.

If both versions have the same text, there is no conflict.

A conflict can also happen while you edit. If a sync brings a newer version of the note while it is open, Libreta does not overwrite it. It saves your text as a conflict copy and says «“Name” cambió mientras la editabas. Tu versión se ha guardado como “Name (conflicto …)”.» (“Name” changed while you were editing it. Your version was saved as “Name (conflicto …)”.)

## Related

- [Handwritten notes](handwriting.md)
- [Titles, tags and the index](organize.md)
- [The assistant](assistant.md)
- [Voice dictation](dictation.md)
- [Settings](../reference/settings.md)
