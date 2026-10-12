# Titles, tags and the index

You can organize a handwritten note without a keyboard. Mark a piece of handwriting as a **title** or a **tag**. Libreta then builds an index for the note and lets you filter the note list by tag.

You need to be signed in with ChatGPT to mark handwriting, because the AI reads the words. See [Sign in with ChatGPT](../setup/chatgpt.md).

## Mark a title or a tag

1. Open a handwritten note and select the «Seleccionar» tool.
2. Draw a box around the handwriting, for example a heading.
3. Tap «Título» (Title) or «Etiqueta» (Tag).
4. Libreta reads the handwriting and shows the text in an editable box. The box is titled «Marcar como título» (Mark as title) or «Marcar como etiqueta» (Mark as tag). Correct the text if needed, then tap OK.

The handwriting is now marked. In the page, a title is highlighted in yellow and a tag in green.

Tag text is normalized: lower case, no `#`, and spaces become `-`. For example, «Viaje 2026» becomes `viaje-2026`.

Marking is one step for undo.

### Marks follow their strokes

A mark belongs to the strokes you selected:

- If you move, resize or recolour those strokes, the mark moves with them.
- If you erase some of the strokes, the mark keeps the rest.
- If you erase all of them, the mark disappears. Undo brings it back.

Marking a title does not make Libreta read the page again for search.

## The index of a note

Open the menu of a handwritten note and tap «Índice» (Index). A list shows the titles of the note in order, each with its page, for example `Viaje a Lisboa · pág. 1`. Tap a title to go to its page.

If the note has no titles, Libreta says «Esta nota aún no tiene títulos. Márcalos con Seleccionar → Título.» (This note has no titles yet. Mark them with Select → Title.)

## Tags and the note list

The category drop-down in the note list has one entry per tag, written `#tag`, after the categories. Choose one to show only the notes with that tag, in any category.

Tags come from two places:

- the tags you mark in handwritten notes;
- tags you type in a text note. A tag is a word that follows `#` with no space, for example `#compras`. `# Title`, with a space, is a heading and not a tag. A `#` inside a word or a link is not a tag either.

## In Nextcloud

Titles and tags also appear in the Markdown of the handwritten note, so you can see them in the Nextcloud Notes app. Libreta writes them above the page images:

```markdown
## Índice

- Viaje a Lisboa (pág. 1)
- Presupuesto (pág. 3)

Etiquetas: #viajes #2026
```

The `## Índice` block appears only if the note has titles, and the `Etiquetas:` line only if it has tags. Libreta regenerates both every time it saves, from the marks. Do not edit them in Nextcloud, because your changes are replaced. The rest of the text of the note is yours and Libreta does not change it.

## Related

- [Handwritten notes](handwriting.md)
- [Text notes](notes.md)
- [Note format](../reference/note-format.md)
