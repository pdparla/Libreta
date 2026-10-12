# Note format

Libreta stores notes as plain files, in the same layout as the Nextcloud Notes app. This page describes every file Libreta reads or writes, on the tablet and on Nextcloud. The two places have the same tree.

- On the tablet, the notes are in the `notes` folder of the app's private storage (`files/notes/`).
- On Nextcloud, they are in your notes folder (by default `Notes`), reached through WebDAV.

You can read, edit and back up these files with any tool. Libreta keeps the tree in sync.

## Layout

```
Notes/
  Shopping.md
  Home/
    Kitchen.md
    Nota a mano 2026-10-11 20.20.md
    .attachments.5wchxopbrt/
      libreta.ink.json
      libreta.text.json
      pagina-1.png
      pagina-2.png
```

| Item | What it is |
|---|---|
| `Shopping.md` | A text note in the root: it has no category. |
| `Home/` | A folder. The folders above a note are its category. `Home/Kitchen/Pasta.md` has the category `Home/Kitchen`. |
| `.attachments.<id>/` | A hidden folder next to the notes that link to it. Holds images and other files. |

Libreta syncs only these files:

- **Notes.** Files that end in `.md` or `.txt`, in a category folder.
- **Attachments.** Files directly inside an `.attachments.<id>/` folder. The allowed extensions are `png`, `jpg`, `jpeg`, `gif`, `webp`, `svg`, `json`, `m4a`, `mp3`, `ogg`, `wav` and `pdf`.

Everything else is ignored: other hidden files and folders, other extensions, and temporary files. Names cannot contain `\`, control characters, empty segments, `.` or `..`.

## Text notes

A text note is a UTF-8 Markdown file.

- **Title.** The first non-empty line, without a leading `#` or list marker. The file name is the title: `Shopping.md`. See [Text notes](../guide/notes.md#the-file-name-follows-the-first-line) for how Libreta cleans the name.
- **Category.** The folder path. `/` separates sub-categories.
- **Duplicates.** If a name is taken in the same folder, Libreta adds ` (2)`, ` (3)` and so on.
- **Tags.** Words after a `#` with no space, like `#compras`. A tag starts with a letter or digit and may contain letters, digits, `_` and `-`. Libreta lowercases tags and ignores repeats. `# Title` is a heading, and a `#` inside a word or a link (`note.md#2`) is not a tag.

### Versions

Libreta compares files by version, not by date:

- On the tablet, the version of a file is a SHA-1 hash of its content (`sha1:…`).
- On Nextcloud, the version is the ETag of the file.

Writes are conditional (`If-Match`, `If-None-Match: *`), so a write never overwrites a version Libreta has not seen. See [Sync](../design/sync.md).

### Conflict copies

When a note changed on both sides, the version on Nextcloud keeps the name, and the tablet's version is saved next to it as:

```
Name (conflicto 2026-10-11).md
```

If that name exists, Libreta adds ` 2`, ` 3` and so on: `Name (conflicto 2026-10-11 2).md`. The copy is written on both sides. A copy made while editing (when a sync changed the note under the editor) uses the same name.

## Handwritten notes

A handwritten note is a Markdown file plus an `.attachments.<id>/` folder. `<id>` is 10 random characters from `0-9` and `a-z`. The folder holds:

| File | What it is |
|---|---|
| `libreta.ink.json` | The strokes, as JSON. See [Ink file](#ink-file). |
| `pagina-N.png` | The image of page N, starting at 1. |
| `libreta.text.json` | What the AI read from each page, for search. Optional. See [Transcripts](#transcripts). |

### The Markdown file

```markdown
# Nota a mano 2026-10-11 20.20

Text that you typed later, or recognised from the handwriting.

## Índice

- Trip to Lisbon (pág. 1)

Etiquetas: #trips

![Página 1](.attachments.5wchxopbrt/pagina-1.png)

![Página 2](.attachments.5wchxopbrt/pagina-2.png)

[libreta-ink]: .attachments.5wchxopbrt/libreta.ink.json
```

From top to bottom:

1. **Your text.** The title line and any text you or the AI add. Libreta keeps these lines as they are.
2. **The index.** A `## Índice` heading followed by `- Title (pág. N)` lines. Only present if the note has marked titles.
3. **The tags.** One line, `Etiquetas: #a #b`. Only present if the note has marked tags.
4. **The page images.** One `![Página N](<folder>/pagina-N.png)` line per page.
5. **The reference line.** `[libreta-ink]: <folder>/libreta.ink.json`.

Libreta manages items 2 to 5. On every save it rewrites them from the strokes and keeps every other line. It recognizes the managed lines only in these exact forms: the `## Índice` heading with its `- … (pág. N)` lines, a line that starts with `Etiquetas: #`, the page image lines, and the reference line. See [Titles, tags and the index](../guide/organize.md).

Links are relative to the folder of the note, so the `.attachments.<id>` folder must stay next to the note. That is the Nextcloud Notes convention for pasted images, so the Notes app shows the pages without any extra step.

The reference line is a Markdown *link reference definition*. Viewers do not show it. It tells Libreta that the note has strokes and where they are.

### How Libreta recognizes a handwritten note

A note is handwritten when it has strokes. Libreta looks for `libreta.ink.json` in this order:

1. the folder named by the `[libreta-ink]:` line;
2. each `.attachments.<id>` folder that the note links to.

The second rule matters because the Nextcloud Notes web editor drops the reference line when it saves a note, as nothing links to it. The images stay. Libreta still recognizes the note, and it writes the line again the next time it saves the note.

### Page images

Each page is a PNG at 2 pixels per page unit, with the paper included. A page of 600×851 units is 1200×1702 pixels. Libreta draws only the pages that changed. Marks (titles and tags) are not drawn, so marking a title does not redraw the page.

### Attachments follow the note

- When a note changes category, its attachments are copied to the new folder and removed from the old one.
- When a note is deleted, the attachment folders it links to are deleted with it.
- A page that you delete removes its `pagina-N.png`.

## Ink file

`libreta.ink.json` holds every stroke of the note.

```json
{"format":"libreta-ink","version":2,"pages":[
  {"width":600,"height":900,"paper":"lined",
   "strokes":[
     {"color":"#ff202124","width":3.5,"start":1760197500000,"points":[12.5,40,0,13,41.5,16]},
     {"color":"#ffffeb3b","width":7.5,"start":1760197600000,"fill":true,"points":[100,201,0,180,201,0]}],
   "marks":[{"kind":"title","text":"Trip","strokes":[1760197500000]}]}]}
```

### Document

| Field | Type | Meaning |
|---|---|---|
| `format` | string | Always `libreta-ink`. |
| `version` | number | `1` or `2`. Libreta writes `2` only when a page has marks, and `1` otherwise. It refuses a file with a higher version. |
| `pages` | array | At least one page. |

### Page

| Field | Type | Meaning |
|---|---|---|
| `width`, `height` | integer | Size in page units. One unit is 1 dp on the tablet. A page can be portrait or landscape. |
| `paper` | string | `lined`, `grid` or `blank`. An unknown value reads as `blank`. |
| `strokes` | array | The strokes, in drawing order. |
| `marks` | array | Titles and tags. Version 2. Optional. |

### Stroke

| Field | Type | Meaning |
|---|---|---|
| `color` | string | `#aarrggbb`. A 6-digit `#rrggbb` is read as opaque. |
| `width` | number | Thickness in page units. |
| `start` | integer | Time of the first point, in milliseconds since the Unix epoch. |
| `points` | array | A flat list of `x, y, t` triples. `x` and `y` are page units rounded to 0.1. `t` is milliseconds since the start of the stroke. The list is never empty and its length is a multiple of 3. |
| `fill` | boolean | `true` for a band of a fill. Written only when true. |

Libreta keeps the time of each point, so moving or resizing ink does not change when it was written.

A **fill** is stored as strokes: horizontal bands of two points each, thick and overlapping, that together cover the shape. All the bands of one fill share the same `start`. Libreta draws them behind the other ink.

### Mark

A mark labels handwriting as a title or a tag.

| Field | Type | Meaning |
|---|---|---|
| `kind` | string | `title` or `tag`. A mark with another kind is skipped when reading. |
| `text` | string | The text. A tag is lower case, without `#`, with `-` between words. |
| `strokes` | array of integers | The `start` values of the strokes the mark is made of. |

## Transcripts

`libreta.text.json` stores the text that the AI read from each page, so that search finds handwriting. It syncs with the note, so another tablet does not read the pages again.

```json
{"format":"libreta-text","version":1,"pages":[
  {"hash":"sha1:3f1c…","text":"Shopping list\n- bread\n- milk"},
  {"hash":"sha1:9a0e…","text":""}]}
```

| Field | Meaning |
|---|---|
| `pages` | One entry per page, in order. |
| `hash` | A fingerprint of the page when it was read: the SHA-1 of the page's JSON, without its marks. When the page changes, the hash no longer matches and the page is read again. |
| `text` | What the page says. Empty for a page without strokes. |

## Conversation notes

«Guardar como nota» in the assistant chat creates a text note:

```markdown
# Conversación IA 2026-10-11 21.40

![Recorte](.attachments.k3f9a2/recorte.png)

**Tú:** What does this say?

**IA:** It says «shopping list».
```

- The image line exists only when the chat started from a selection. The picture is saved first as `recorte.png` in a new `.attachments.<id>/` folder, so the note never points at a missing file.
- Every message is a paragraph that starts with `**Tú:**` (you) or `**IA:**` (the assistant).
- The note is a normal text note: it syncs and moves like any other.

## Clean copies

«Pasar a limpio» creates a normal text note called `Title (a limpio)`. It has a `#` title, the text, and a last line in italics that says which handwritten note it comes from and when. It has no special format.

## Related

- [Text notes](../guide/notes.md)
- [Handwritten notes](../guide/handwriting.md)
- [Sync](../design/sync.md)
