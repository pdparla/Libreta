package com.parla.libreta.core.ink

import com.parla.libreta.core.note.NoteNaming
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.WritePrecondition
import com.parla.libreta.core.note.writeText

/**
 * The text note a clean copy creates (docs/design/ai.md#clean-copy), with the pictures of its drawings as
 * attachments, the way Nextcloud Notes keeps pasted images:
 *
 * ```markdown
 * # Física (a limpio)
 *
 * ## Circuitos
 *
 * ![Esquema del circuito](.attachments.k3f9a2/dibujo-1.png)
 *
 * _Pasada a limpio de la nota a mano «Física» el 2026-10-16 18.30._
 * ```
 */
class CleanCopyNotes(
    private val notes: NoteRepository,
    private val newFolderId: () -> String,
) {

    /**
     * Creates the note titled [title] in [category] from the model's [body] and a last line, [footer]. Each drawing
     * placeholder in [body] ([InkFigures]) becomes a link to its picture in [figures], by position; a placeholder whose
     * picture is null (no strokes where the model pointed) is left out. Pictures are written first, so the note never
     * points at a missing file.
     */
    fun save(category: String, title: String, body: String, footer: String, figures: List<ByteArray?>): NotePath {
        val path = NoteNaming.uniquePath(title, category, isTaken = { notes.exists(it) })
        val folder = NotePath.ATTACHMENTS_FOLDER_PREFIX + newFolderId()
        var written = 0
        val text = InkFigures.replace(body) { i, placeholder ->
            val image = figures.getOrNull(i) ?: return@replace null
            val name = figureName(++written)
            val target = path.resolveSibling("$folder/$name") ?: throw IllegalArgumentException("Bad folder $folder")
            notes.write(target, image)
            "![${captionOf(placeholder.caption, written)}]($folder/$name)"
        }
        notes.writeText(path, "# ${title.trim()}\n\n$text\n\n$footer\n", WritePrecondition.MustNotExist)
        return path
    }

    companion object {
        fun figureName(number: Int) = "dibujo-$number.png"

        /** Brackets would end the image's text early. */
        private fun captionOf(caption: String, number: Int): String =
            caption.replace('[', '(').replace(']', ')').trim().ifEmpty { "Dibujo $number" }
    }
}
