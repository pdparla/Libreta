# Handwritten notes

A handwritten note is a canvas for writing or drawing with a finger or a capacitive stylus. It has one or more pages. Libreta syncs it with Nextcloud as a Markdown file with one image per page, so the Nextcloud Notes app shows the pages. The tablet keeps the strokes, so you can keep editing them. See [Note format](../reference/note-format.md).

## Create and open a note

1. In the note list, tap the pen icon («Nota a mano», Handwritten note). A blank page opens.
2. Write or draw.
3. Tap the back arrow.

The note is created only when you draw something. Its default title is `Nota a mano 2026-10-11 20.20`, which is the date and time. Change it with «Título…» in the menu.

In the list, a handwritten note is marked «A mano» (Handwritten). Tap it to open it.

## The screen

The page fills the screen. Two rows of controls are below it.

The first row has the tools and the page controls:

| Control | What it does |
|---|---|
| Pen («Pluma») | Draws. |
| Eraser («Borrador») | Erases. |
| Select («Seleccionar») | Selects ink to move, resize, recolour, copy, delete or send to the AI. |
| Fill («Rellenar») | Fills a closed shape with colour. |
| Undo («Deshacer») and redo («Rehacer») | Go back and forward through your changes. |
| Microphone | Dictates text. See [Voice dictation](dictation.md). |
| Arrows, `1/3` and page icon | Go to the previous or next page, show the page number, and add a page. |

The second row shows the options of the tool you chose.

The page also has zoom controls in its lower-right corner. See [Zoom](#zoom).

The menu (three dots) has these items:

| Item | What it does |
|---|---|
| «Título…» (Title…) | Renames the note. |
| «Categoría» (Category) | Moves the note to another category. |
| «Índice» (Index) | Lists the titles you marked. See [Titles, tags and the index](organize.md). |
| «Papel» (Paper) | Sets the paper of the current page. |
| «Pasar a limpio» (Make a clean copy) | Creates a text note from the whole handwritten note. See [The assistant](assistant.md#make-a-clean-copy-of-a-note). |
| «Editar el texto» (Edit the text) | Opens the note's Markdown in the text editor. |
| Trash icon | Deletes the note after you confirm. |

Libreta remembers your last pen colour, pen width, eraser mode and eraser size. They apply in every note.

## Pen

Select the pen. The second row shows:

- A colour swatch. Tap it to open a palette of 72 colours: a row of greys and, for each of 12 hues, five shades from dark to light. The default is near-black.
- A slider for the width, a sample of the stroke in the current colour and width, and the number.

The width slider is continuous. It goes from 0.5 (a very fine line) to 40 (a very thick line). The default is 3.5. The slider moves faster between thick sizes than between thin ones, so there is as much travel between 1 and 3 as between 13 and 40. The sample shows the real size on the screen.

Stroke widths are what you see on the screen. When you write with the page zoomed in, Libreta stores the stroke thinner, so that it looks in proportion when you zoom out.

## Eraser

Select the eraser. The second row shows two modes, a size slider, a sample and the number:

- «Donde toco» (Where I touch) is the default. It erases only the ink under the eraser and cuts a stroke in two if you erase its middle.
- «Trazo entero» (Whole stroke) removes the whole stroke you touch.

The size slider sets the eraser radius, from 2 to 80. The default is 10. A circle shows the erased area while you erase.

One pass of the eraser is one step for undo.

## Undo and redo

Libreta remembers up to 100 steps. Each of these is one step: a stroke, one pass of the eraser, and each selection edit (move, resize, colour, width, delete, paste).

Undo, redo, changing page and changing tool clear the current selection.

## Pages and paper

- The page icon adds a page after the current one, with the same size and paper.
- The arrows move between pages. The number shows the current page and the total, for example `2/3`.
- «Papel» sets the paper of the current page: «Pautado» (Lined, the default), «Cuadrícula» (Grid) or «En blanco» (Blank). Each page has its own paper.

## Select, move and edit ink

Select the «Seleccionar» tool. The second row says «Marca un recuadro; luego arrástralo o tira de su esquina» (Draw a box, then drag it or pull its corner).

1. Drag across the page to draw a box.
2. When you lift the finger, Libreta selects every stroke that has some ink inside the box, and the box shrinks to fit those strokes. A blue handle appears at its lower-right corner.

Then:

| Gesture or button | Result |
|---|---|
| Drag inside the box | Moves the selected strokes. |
| Drag the blue handle | Resizes the strokes in proportion, from the upper-left corner. The stroke width scales too. |
| Drag outside the box | Starts a new selection. |
| Colour swatch («Color de lo seleccionado») | Opens the palette and recolours the selection. |
| Width button («Grosor de lo seleccionado») | Opens the width slider, starting at the width of the first selected stroke. Fills keep their own width. |
| Copy («Copiar lo seleccionado») | Copies the strokes. A message says «Copiado. Pégalo en esta u otra página con «Pegar».» |
| Trash («Borrar lo seleccionado») | Deletes the selection. |
| Paste («Pegar») | Appears when something is copied. It pastes slightly below and to the right of the original, inside the page, and selects the copy so you can place it. |

You can paste on the same page, on another page, or in another handwritten note. The copied strokes are kept in the app's memory. They are lost if Android closes the app.

If the box holds no ink, it stays as you drew it. Only the AI buttons work.

The text buttons in the same row are:

- «A texto» (To text): converts the selection to text. See [Convert a selection to text](#convert-a-selection-to-text).
- «Chat IA» (AI chat): opens a chat about the selection. See [The assistant](assistant.md#chat-about-a-selection).
- «Título» (Title) and «Etiqueta» (Tag): mark the selection as a title or a tag. See [Titles, tags and the index](organize.md).

## Fill shapes

Select «Rellenar». The second row shows the colour (the same as the pen) and the hint «Toca dentro de una figura cerrada para rellenarla» (Tap inside a closed shape to fill it).

- Tap inside a closed shape. It fills with the colour up to the ink around it. The fill sits behind the ink, so the outline and anything you wrote inside stay visible.
- A small gap in the outline, up to a few millimetres, does not let the fill escape.
- Tap inside a filled shape again to change its colour.
- If you tap outside any shape, the open area fills up to the edges of the page. Undo it with one step.
- If you tap right on the ink, nothing is filled. Libreta says «Toca dentro de la figura, no encima de la tinta.» (Tap inside the shape, not on the ink.)

A fill is ink like any other. You can undo it, erase it, and select, move, copy or recolour it. With «Trazo entero», the eraser removes a whole fill. When your selection touches a fill, the selection takes the whole fill.

## Zoom

You can zoom the page from 1× (the whole page) to 4×.

- Pinch with two fingers to zoom in or out around the point between your fingers.
- Drag with two fingers to move the zoomed page.
- Use the controls over the lower-right corner of the page: **−**, the current zoom (for example «2×»), and **+**. They go through 1×, 1.5×, 2×, 3× and 4×. Tap the zoom level to return to the whole page («Ver la página entera»).

You write, erase and select the same way at any zoom. What you write at a high zoom is smaller on the page, which gives you room to write more.

Zoom is not saved with the note. A note always opens at 1×. When you change page, the zoom stays and the view goes to the page's upper-left corner.

If a second finger touches just after the first one (within about 150 ms, or before the first has moved much), Libreta drops the stroke it had started and treats both fingers as a gesture. If you are already drawing a stroke, a second finger is ignored, so a resting hand does not zoom.

## Portrait and landscape

The note rotates with the tablet. You keep your strokes, undo history, tool, selection and zoom.

- In landscape, a portrait page is fitted to the width of the screen. That is 1×. Drag with two fingers to move down the page.
- A new note that you create in landscape has a landscape page, because a page takes the size of the writing area.
- When you tap the page icon while the tablet is held the other way from the current page, a dialog asks «Vertical» (Portrait) or «Horizontal» (Landscape) for the new page. When the orientations match, the new page matches the current one.

Pages in one note can have different sizes. The image of each page has its own size in Nextcloud.

## Convert a selection to text

You need to be signed in with ChatGPT. See [Sign in with ChatGPT](../setup/chatgpt.md).

1. Select the handwriting.
2. Tap «A texto». A message says «Reconociendo la escritura…» (Reading the handwriting…). You can cancel it.
3. A box titled «Texto reconocido» (Recognised text) shows the result. Correct it if needed.
4. Tap «Añadir a la nota» (Add to the note) to put the text in the note. Or tap «Copiar» (Copy) to copy it.

The text is added at the end of the text of the note, above the page images. The strokes do not change, and the text shows in Nextcloud Notes. If nothing is recognised, a message says «No se ha reconocido texto en el recuadro.» (No text was recognised in the box.)

If you select an area with no ink, a message says «No hay nada escrito en el recuadro.» (There is nothing written in the box.)

## Saving

Libreta saves a handwritten note when you leave it or switch the screen off, and also 3 seconds after you stop touching the screen, if something changed. It does not save while you write, because saving draws the page and you would notice it in the stroke. If the app closes without warning, you lose at most the last 3 seconds of strokes.

## Related

- [Titles, tags and the index](organize.md)
- [The assistant](assistant.md)
- [Text notes](notes.md)
- [Note format](../reference/note-format.md)
