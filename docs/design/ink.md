# Handwriting (ink)

This document describes how handwritten notes are modelled, stored, edited and drawn. To use the feature, see
[Handwriting](../guide/handwriting.md). For the files on Nextcloud, see [Note format](../reference/note-format.md).

The logic that doesn't need Android is in `:core` (`com.parla.libreta.core.ink`) and has JVM tests. The Android
parts are in `:app` (`com.parla.libreta.ui.ink`): `InkView`, `InkRenderer`, `InkNoteActivity`, `InkClipboard` and
`InkTranscriber`.

## Model

All types are immutable.

| Type | Description |
|---|---|
| `InkPoint` | `x`, `y` in page units, and `t`, milliseconds since the stroke began. |
| `Stroke` | `color` (ARGB), `width`, `startedAt` (epoch milliseconds of the first point), the points, `fill`, and `text` for typed text. |
| `InkPage` | `width`, `height` in page units, a `Paper` (`blank`, `lined` or `grid`), the strokes in drawing order, and the marks. |
| `InkMark` | A title or tag you marked: its text, and the strokes it's made of, identified by their `startedAt`. |
| `InkDocument` | One or more pages. Each page has its own size, so an upright note can have landscape pages. |

**Page units.** One unit is 1 dp on the tablet at the time the page is created. Strokes, selections and sizes are
all in page units, so zooming and rotating only change how a page is shown. A new note takes its page size from the
writing area at that moment, which is why a note started in landscape has a landscape page.

**Time.** Each point keeps its time. The order and speed of strokes help handwriting recognition, and moving ink
doesn't change when it was written: `Stroke.transformed` keeps the times. Erasing cuts a stroke into pieces, and
each piece starts its own clock, so `t` always means "milliseconds after this stroke began".

**Identity.** `startedAt` ties several things together: the bands of one fill share it, and a mark refers to its
strokes by it. Moving or recolouring a stroke doesn't change it, so marks follow their strokes.

### JSON

`InkJson` writes the document to `libreta.ink.json`. It's a hand-written codec, because `org.json` is only a stub
in JVM tests, and bundling another copy would clash with the one in Android.

```json
{"format":"libreta-ink","version":3,"pages":[{"width":600,"height":851,"paper":"lined","strokes":[
  {"color":"#ff202124","width":3.5,"start":1760197500000,"points":[12.5,40,0,13,41.5,16]},
  {"color":"#ffffeb3b","width":7.5,"start":1760197600000,"fill":true,"points":[100,201,0,180,201,0]},
  {"color":"#ff202124","width":24,"start":1760197700000,"text":"Hola","points":[60,300,0,112,328,0]}],
  "marks":[{"kind":"title","text":"Trip","strokes":[1760197500000]}]}]}
```

- Points are a flat array of `x, y, t` triples, to keep the file small. Coordinates are rounded to 0.1.
- `fill` is written only when true, `text` only for typed text, and `marks` only when a page has some. A document
  without marks or typed text is written as `"version":1`, exactly as before marks existed, so its fingerprints don't
  change. With marks it is version 2, and with typed text version 3. The decoder accepts any version up to 3, and
  rejects a newer one: an older app refuses a note with typed text instead of opening it and saving the text as a
  bare line.
- Unknown mark kinds are skipped instead of failing the whole note.

## Editing

`InkEditor` is an editing session on an `InkDocument`. Every change produces a new immutable document, so undo is
going back to a previous one.

- **Undo and redo.** A stack of snapshots (document and page index), up to 100 steps. A new change clears the redo
  stack.
- **Gestures.** `beginGesture` and `endGesture` group everything in between into one step. Rubbing the eraser
  across the page is one step. `cancelGesture` goes back to the start of the gesture and leaves no step: a touch
  that turns out to be the start of a pinch must not leave an erasure behind.
- **Every edit is one step:** adding a stroke, erasing, moving or scaling, recolouring, deleting, pasting, filling,
  marking, adding a page and changing the paper. An edit that changes nothing adds no step.
- **Marks stay in step with strokes.** After each change, a page that the change replaced drops any mark whose
  strokes are all gone.
- **Saved state.** `isModified` compares the document with the one last saved. `markSaved(document)` accepts a
  snapshot, so edits made while a background save runs still count as unsaved. See
  [Architecture](architecture.md#handwritten-notes).

### Erasing

Two modes, both with a radius in page units:

- **Where I touch** (`eraseAreaAt`). It cuts each stroke the eraser crosses, keeping the pieces on either side.
  The geometry is exact: the stroke's own thickness counts, and a stroke is cut where the segment enters and
  leaves the eraser's disc. Pieces shorter than 0.5 units are dropped. The scan allocates nothing until a stroke
  changes, because most touch points hit nothing.
- **Whole stroke** (`eraseStrokesAt`). It removes every stroke the eraser touches, and any fill it touches, whole.

A cheap bounding-box test (`isOutsideDisc`) runs first for every stroke. Both modes remove typed text whole when the
eraser touches its box: half a word makes no sense.

### Sizes

`InkSizes` maps a slider of 1000 steps to a size on an exponential scale: `min * (max/min)^(p/1000)`. There's as
much travel between 1 and 3 as between 13 and 40, which is where you notice the difference. The pen goes from 0.5
to 40 units and the eraser radius from 2 to 80. The mapping is continuous, monotonic and reversible, and tests
check it. Older saved sizes (the three fixed ones) are values on the same scale.

Sizes are as they look on screen. At zoom *z*, the app stores a pen stroke with width `penWidth / z`, and uses an
eraser radius of `radius / z`. The mark looks the same on screen while you write, and in the whole page it's in
proportion to the smaller letters.

## Selection

The Select tool marks a rectangle. When you release, `InkRect.selectedStrokes` picks every stroke with ink inside
it, and the rectangle snaps to a box around those strokes (`InkRect.around`, which counts line thickness). If no
ink is inside, the rectangle stays as drawn and only the AI actions apply.

- A stroke is "in" the rectangle if one of its points is inside, or a line between two points crosses it
  (`Stroke.touches`, using Liang-Barsky clipping). The segment test matters for fills, whose bands are long lines
  of two points, and for fast strokes with few points.
- A selection that touches part of a fill takes the whole fill, using the shared `startedAt`
  (`InkPage.withWholeFills`). The whole-stroke eraser does the same.
- The selection is a list of stroke indexes in the current page, plus the rectangle. Undo, redo, changing page and
  changing tool clear it.
- Moving and scaling apply `InkEditor.transformStrokes`, which scales around a pivot and then moves, and scales the
  stroke width with it. `moveWithin` and `scaleWithin` keep the result inside the page and above a minimum side
  (`MIN_SIDE`).
- Colour and width use `restyleStrokes`. The width of a selection doesn't apply to fills, because their bands have
  just enough thickness to cover the shape, nor to typed text, whose width is its font size.
- Copy goes to `InkClipboard`, which holds the strokes in the process memory. Only Libreta understands strokes,
  so the system clipboard isn't used, and the contents are lost if Android ends the process. Paste places them 16
  units down and to the right of the original, kept on the page.

While you drag a selection, the app doesn't rebuild strokes. It redraws the cache once without the selected
strokes, builds their `Path` objects once, and then draws them each frame with a canvas translation and scale.
The change enters the document as one undo step when the finger lifts. A cancel mid-drag leaves everything as it
was.

## Fill

`InkFill.at` computes a paint-bucket fill. The result is ordinary ink, so erasing, selecting, moving, copying,
recolouring and the page images in Nextcloud all work with fills with no extra code.

A fill is stored as **horizontal bands**: strokes of two points, one for each row, thick and overlapping, which
together cover the area. The bands carry `"fill":true` and share one `start`, which identifies the fill. The field
is new and optional, so older versions of the app show the bands as normal strokes.

Algorithm:

1. Rasterise the page into a grid. Each cell that touches ink (other fills not counted) is blocked, with the ink
   thickened by a margin *g* so that small gaps in an outline don't let the fill escape. Typed text blocks its whole box.
2. Flood-fill the free area connected to the tapped cell. It's a scanline fill with an explicit stack, with no
   recursion.
3. Every *s* units of height, starting at the first row of the area, take the row. Each continuous run of free
   cells becomes a band of thickness `s + 2(g + cell)`. The thickness and the round ends give back the margin that
   step 1 took away (plus one cell, because the first free cell can be up to a cell beyond the edge), so the
   fill reaches the ink without crossing the outline.

Sizes: *g* is 3 units on screen and *s* is *g* / 2, both divided by the zoom, so you can fill a small shape drawn
while zoomed in. The cell is 1 / zoom units, but never smaller than keeps the grid within 1,500,000 cells
(`MAX_CELLS`, about 1.5 MB of one byte per cell, on a device with a 64 MB heap).

Behaviour:

- **Order.** New bands go to the bottom of the page: before the first non-fill stroke and after the existing
  fills. Ink stays on top of colour, and a new fill is above older fills.
- **Filling again.** Old bands that cross the new area lose only the part inside it. Bands entirely inside
  disappear, which recolours the area, and bands that cross it keep their outside pieces with the same `start`.
  All of this is one undo step.
- **Open areas.** A tap outside any closed shape fills the open region up to the page edges. A tap directly on ink
  fills nothing, and the app says so.

## Typed text

«Pasar a letra» turns handwriting into the same words in a font (see [Typing](ai.md#typing) for how the text is read
and sized). Typed text is a `Stroke` with a `text`:

- Its two points are the upper-left and lower-right corners of the text's box, and its `width` is the font size.
  `bounds` is that box, without the half-thickness ink adds.
- Being a stroke, it needs no other code to be selected, moved, scaled, recoloured, copied, pasted, undone, marked as
  a title or tag, compared by `InkDiff`, or drawn into the page images. Scaling multiplies the corners and the width by
  the same factor, so the box and the font size stay in step.
- `:core` knows only the box. A selection takes the text when the rectangle touches the box, and the eraser when it
  touches the box. A fill treats the whole box as ink, since the shapes of the letters are not known there.
- `InkText` (`:app`) lays it out and draws it, with the system's sans-serif font (Roboto): one line under another
  from the upper-left corner, `fontSpacing` apart. The box is measured with the same paint when the text is created
  or corrected, so it matches what is drawn. While a selection is dragged, the text becomes a path of its letters
  (`Paint.getTextPath`) like any other moving stroke.
- Dictated text is put where the user taps (`InkView.placeAt` takes that one tap instead of the tool): the first
  line centred on the tap, 20 units at 1× (divided by the zoom), wrapped to the page's right edge less 16 units, and
  moved up or made smaller if it would leave the page. The line breaks become part of the text: it is laid out once,
  so it never reflows when zoomed.
- Correcting the text keeps the upper-left corner and the size, unless the longer text would run off the page: then
  it gets smaller, down to 8 units, and moves in if it still doesn't fit.

## Clean tables

«Tabla a limpio» turns a table drawn by hand into straight lines and typed text, in one undo step
(`InkEditor.replaceStrokes`). `InkTable` (`:core`) holds the logic:

- **Reading.** `AiAssistant.readTable` asks for a Markdown table with the same rows and columns, the first row as
  header, `<br>` for a break inside a cell and empty cells left empty. `InkTable.parse` takes the first table in the
  answer, drops the separator row, reads `\|` as a bar and `<br>` as a line break, and pads short rows. A Markdown
  table is easy to check and is what the note's text needs anyway, so no other format is asked for.
- **Layout.** `InkTable.layout` measures every cell's text at font size 1 (`InkText.sizeAtOne`). A column is as wide
  as its widest text and a row as tall as its tallest, plus padding (0.4 and 0.25 font sizes). The font size is the
  largest at which that fits in the box of the drawn table, from 8 to 60 units. The spare room then widens the columns
  and heightens the rows in proportion, so the clean table covers the drawn one. At 8 units a table that does not fit
  grows past the box.
- **Strokes.** The grid is one straight two-point stroke per row edge and per column edge
  (`InkTable.gridStrokes`), as thick as the median width of the ink it replaces and in its colour. Each non-empty cell
  is one typed text, padded from the left and centred in its row. Every stroke gets its own `startedAt`.
- **The note's text.** `InkTable.toMarkdown` writes the table back and `InkNoteService.addText` adds it above the
  page images, so the table shows in Nextcloud Notes and search finds it. Undo restores the drawing but not the
  text, which lives in the Markdown, outside the undo history.

## Clean diagrams

«Diagrama a limpio» turns a sketched diagram into straight shapes, lines and typed text, in one undo step.
`InkDiagram` (`:core`) holds the logic:

- **Reading.** `AiAssistant.readDiagram` asks for JSON: `nodos` (an id, the text, the shape, `rectangulo`, `elipse`,
  `rombo` or `ninguna` for text alone, and a `caja`) and `conexiones` (`desde`, `hasta`, `flecha`: `fin`, `ambas` or
  `ninguna`, and a `texto`). The boxes are in thousandths of the selection image, which `InkFrame.around` of the
  selection maps back to the page, as for the drawings of a clean copy. `InkDiagram.parse` skips nodes without an id
  or a box, repeated ids, and edges that do not join two different nodes. Anything that is not such JSON means no
  diagram.
- **Alignment.** `aligned` puts nodes whose centres are within 10 units across or down at their average, which is
  most of what makes a drawn diagram look clean. Sizes are kept.
- **Strokes.** Each outline is one stroke: a rectangle or a diamond of five corners, an ellipse of 64 segments. A line
  runs between the centres of two nodes, cut where it meets each outline (box, ellipse or diamond) with a gap of 3
  units plus the line width, and an arrowhead is a three-point stroke at 25° to the line, 3 line widths long (at least
  8 units). Lines are as thick as the median width of the ink they replace, in its colour.
- **Sharp corners.** Strokes are drawn smoothed through the midpoints of their points (see [Drawing](#drawing)), which
  would round a corner. `InkDiagram.sharp` doubles every corner of the rectangles, diamonds and arrowheads, so each
  curve's control point lies on its own straight segment, the edges stay straight and the corners sharp.
- **Texts.** The app types each node's text in the middle of its shape: within 85% of a rectangle, 70% of an ellipse,
  50% of a diamond, or the whole box for text alone. Every text gets the median of the sizes they could each take
  (at most 32 units), or less where it does not fit, so the diagram reads as one. An edge's text is three quarters of
  that size, beside the middle of a steep line or above a flat one.

## Circled questions

A question written and circled on the page is answered by the AI (see [Circled questions](ai.md#circled-questions)).
`InkLoops` (`:core`) decides on the tablet what counts as a circle, so that only likely questions cost a request:

- **A loop** is one pen stroke (not a fill or typed text) of at least 8 points, at least 40 units across and 15
  thick, whose ends meet within 30% of its larger side, and whose length is at least 1.3 times its box's width plus
  height (a circle is about 1.57). Lines, open arcs, small letters and back-and-forth scribbles are not loops.
- **What it holds** is every other stroke, fills left out, whose box lies within the loop's box and whose box centre
  is inside the loop, closed from its last point to its first (ray casting).
- **When.** After each touch with the pen, if the page's last stroke is a loop holding at least two strokes and was not
  asked about before, the question is asked once the pen has rested 1.5 seconds. Touching the page again cancels it,
  so the circle of a drawing in progress is not sent, and a question mark written after the circle still gets in.
  Each loop is asked about once per opening of the note, by its `startedAt`.
- **The answer** is typed text, 16 units at 1× (divided by the zoom), in blue (`#1a73e8`). It goes to the right of the
  loop, with its first line at the loop's middle height, when there are 140 units of room; otherwise under it. It is
  wrapped and kept on the page like dictated text, and added as one undo step. It is dropped if the loop is gone or
  the user is on another page, and it waits while a finger is on the page so that it never lands in the middle of a
  stroke.

## Viewport, zoom and orientation

`InkViewport` (in `:core`, without Android) converts between page and screen:

- `scale = fitScale x zoom`. `fitScale` makes the whole page fit in the view, except for an upright page in a
  landscape view, which fits the width and scrolls vertically (a whole page would be too small to write on).
- `originX`, `originY` is where the page's top-left corner falls on screen, in view pixels.
- `toPageX/Y` and `toViewX/Y` convert points. `zoomAround(focusX, focusY, zoom)` zooms without moving what is
  under the fingers. `panBy` moves the page.
- Limits: zoom goes from 1x to 4x, and the + and - buttons step through 1, 1.5, 2, 3 and 4. On an axis where the
  page is smaller than the view, it's centred horizontally and kept at the top vertically; where it's larger, it
  can't be moved past its edges, so you never see empty space around a zoomed page.

Every conversion in `InkView` (pen, eraser, selection, resize handle) goes through the viewport. The zoom isn't
saved with the note: it opens at 1x.

### Two fingers and the stylus

The screen is capacitive, so the stylus looks like a thin finger. The first contact draws. A second contact that
arrives while the first is fresh (under 150 ms, or less than 8 dp travelled) turns both into a pinch: what the
first started is dropped. That means the pen discards the stroke, the eraser calls `cancelGesture`, and the
selection cancels its rectangle or drag. If the first contact has already drawn something, the second is ignored,
so a resting hand doesn't zoom. The gesture lasts until every finger lifts. Contacts larger than a threshold
(`maxTouchMajor`) can be ignored as palms; the default of `0` accepts any contact.

### Rotation

`InkNoteActivity` and `AiChatActivity` declare `configChanges="keyboardHidden|orientation|screenSize"`, so Android
doesn't recreate them on rotation. The note, the undo history and the conversation stay in memory, and the views
adjust to the new size.

- `InkView.onSizeChanged` calls `InkViewport.resized`, which keeps the zoom and the page point at the centre. An
  active stroke, erase or drag is cancelled as if by `ACTION_CANCEL`.
- The toolbar stays on two rows in landscape. Without recreating the activity you can't switch layouts without
  rebuilding the views, and the selection row doesn't fit on one line of 1024 px beside the tools.
- When you add a page and the screen's orientation differs from the current page's, the app asks whether the new
  page is upright or landscape. `InkEditor.addPage` takes the size of the new page, and a landscape page has the
  upright page's width and height swapped.

## Drawing

`InkRenderer` draws pages and strokes. A stroke is a smooth path: straight to the first midpoint, then quadratic
curves through the midpoints of the samples, with round caps and joins. A one-point stroke is a circle.

The paper is lined (40-unit spacing, a 64-unit top margin and a 48-unit left margin), a grid (20-unit step) or
blank. The ruling's thickness is `max(1, scale)`, so a 1-pixel line doesn't vanish when a page image is reduced.

### Cache and partial redraw

`InkView` keeps a bitmap the size of the view with the paper, marks and finished strokes. Only the stroke being
drawn is painted on top of it each frame. Several rules keep that cheap on this tablet:

- **Culling.** A redraw paints only the strokes whose bounding box touches the visible area.
- **Pinching.** During a pinch the page isn't redrawn. The cache is drawn scaled and moved by the canvas, and
  redrawn sharp when the fingers lift. The - and + buttons redraw directly.
- **Partial redraw.** `refreshAfter` redraws only what changed after undo, redo, paste, delete, recolour, mark and
  fill, using `InkDiff`. It falls back to a full redraw for another page, or a page with another size or paper.
  `refreshArea` clips the canvas to the dirty rectangle, plus 2 pixels of slack for antialiased edges, and limits
  it to the part of the bitmap that is on screen. It falls back to a full redraw if the cache was drawn for another
  viewport.
- **One drawing path.** `drawPage` serves both the full and partial redraws, so a partial redraw can't differ from a
  full one.

### InkDiff

`InkDiff.dirtyArea(before, after, hidden)` returns the rectangle, in page units, that covers everything that looks
different between two versions of a page with the same canvas (`sameCanvas`: same size and paper), or null if
nothing does. An over-large rectangle is harmless and an under-sized one leaves ghost ink, so it errs on the large
side.

It includes:

- Strokes that are in only one page, compared by identity. An edit leaves untouched strokes as the same objects, and
  a stroke's `equals` walks all its points. Repeated objects count: the same object twice is two strokes.
- The highlight of every mark whose strokes changed, or that was added, removed or edited, both as it was and as it
  is. A mark's highlight is its strokes' box padded by 4 units.
- `hidden`: strokes that the cache was drawn without (the ones being moved), which are drawn again now.

### Page images

The page images that go to Nextcloud are rendered by `InkRenderer.render`, at 2 pixels per unit in RGB 565 (a page
has no transparency, and that halves the memory of the bitmap while saving). A 600 x 851 page becomes
1200 x 1702 pixels, and a lined page with few strokes is about 12 KB as PNG. At 1 pixel per unit, writing done zoomed
in would be unreadable. Only the pages that changed are rendered again. Marks aren't drawn in the images.

The formatted view of a note would need about 8 MB per page at that size, which is a lot of a 64 MB heap.
`Markdown.forNote` therefore uses its own image decoder, which reduces each image to the screen width while
decoding (`inSampleSize`).

For text recognition, `renderSelection` draws only the ink in a rectangle on white, without the paper's lines
(which can be mistaken for writing), with a margin of at least 16 units or 10% of the rectangle. It uses
2 pixels per unit, or fewer to keep the longer side within 1600 pixels, in RGB 565. `InkFrame` (`:core`) computes
that area and scale, so a point the model names in the image maps back to the page. It's heavy, so call it off the
main thread.

## Titles and tags

You can mark handwriting as a title or a tag. The text is read by the AI and confirmed by you. A mark is stored on
the page with the `startedAt` of its strokes. `InkOutline` collects titles (in page order, then top to bottom
within the page) and tags (in order, without repeats), and `InkNoteFormat` writes them into the Markdown as an
index and a tags line, so they show in Nextcloud and in the note list's tag filter. Marking doesn't change what a
page looks like to the transcription, so it doesn't trigger a new reading. See [Organize](../guide/organize.md).

## Search by handwriting

Search finds handwriting by its transcription. The app reads each page in the background with the same model as
"convert to text", and stores the result with the note.

### The transcription file

`libreta.text.json` sits next to `libreta.ink.json` in the attachments folder:

```json
{"format":"libreta-text","version":1,"pages":[
  {"hash":"sha1:...","text":"Shopping list\n- bread\n- milk"},
  {"hash":"sha1:...","text":""}]}
```

- It has one entry per page, in order. `hash` is the fingerprint of the page when it was read: a hash of the
  page's JSON without its marks (strokes, size and paper). If the page changes, the hash stops matching, and the
  page is pending. `InkTranscripts.pending` returns the pages that are missing or whose hash differs.
- Being in the attachments folder, it syncs with Nextcloud and moves or is deleted with the note, so another tablet
  doesn't read the same page again.
- `InkTranscriptService.save` merges: it reads the file, replaces one page's entry, drops entries for pages the note
  no longer has, and writes with a precondition (`MustMatch`, or `MustNotExist` for the first time), trying up to
  three times. A transcription saved in between isn't overwritten.

### The background pass

`InkTranscriber` runs on its own thread. A pass walks the handwritten notes and, for each, reads the pending pages
one at a time and stores each result at once, so an interrupted pass loses nothing.

- A page without strokes is stored with empty text, without calling the model.
- The page is sent with only its ink, on white, as described in "Page images".
- A pass starts when you leave a handwritten note (after it has been saved) and when the note list opens. It never
  runs while you write. Only one pass runs at a time.
- A pass stops quietly on the first error: no ChatGPT session, no network or the plan's usage limit. What's left is
  retried on the next pass. It doesn't retry in a loop.
- When a pass read something, the note list refreshes so that search finds it.
- The setting "Transcribir lo escrito a mano para buscarlo" (Transcribe handwriting for search) turns it off. It's
  on by default and also shows how many pages are pending.

### The ledger

A pass would otherwise open every note and hash every page to learn that nothing changed. `InkTranscriptLedger`
avoids that:

- After a pass finds a note with nothing pending (it also records notes that aren't handwritten), the ledger
  remembers the note as settled, with the versions of the files it depends on: the Markdown, the strokes and the
  transcription file. A file that doesn't exist counts as a version of `null`.
- Each pass does one `list()` and skips any settled note whose dependency versions are all the same. If a version
  changes, or one of the files appears or disappears, the note is examined again.
- The versions must come from a listing made before the content was read, so a later change can only make the record
  stale, never wrong.
- A note that was just transcribed isn't recorded in the same pass, because saving changed its transcription file.
  The next pass finds nothing pending and records it, at the cost of opening one note.
- The ledger is in memory, is safe for several threads, and forgets notes that no longer exist. The count of pending
  pages in the settings uses the same ledger.

### Search itself

`NoteSearch` normalises text (lower case, accents removed with `java.text.Normalizer`) and looks for the query in the
title, the text and each page's transcription, in that order. It returns where it matched and a snippet of about
60 characters, adjusted to whole words when there's a space within 15 characters. For a handwritten note, it
doesn't search the lines the app manages (page images and the strokes reference).

`NotebookIndex` is the in-memory index behind it, which the AI tools also use. It keeps each note's entry with the
versions of what it read (the Markdown, the strokes if any, and the transcription file or its absence). With one
`list()` of the repository it re-reads only the notes whose versions changed, and drops the ones that are gone. An
index in memory is enough for hundreds of notes.
