package com.parla.libreta.core.ai

import com.parla.libreta.core.note.NoteNaming
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.WritePrecondition
import com.parla.libreta.core.note.writeText

/** Who wrote a message of a conversation with the AI. */
enum class Speaker { USER, ASSISTANT }

data class ChatMessage(val speaker: Speaker, val text: String)

/**
 * A conversation saved as a note, so it syncs like any other:
 *
 * ```markdown
 * # Conversación IA 2026-10-11 21.40
 *
 * ![Recorte](.attachments.k3f9a2/recorte.png)
 *
 * **Tú:** ¿Qué pone aquí?
 *
 * **IA:** Pone «lista de la compra»…
 * ```
 */
class ConversationNotes(
    private val notes: NoteRepository,
    private val newFolderId: () -> String,
) {

    /**
     * Creates the note titled [title] in [category], with [image] (the selection that started the conversation,
     * PNG) as an attachment when given. The image goes first, so the note never points at a missing file.
     */
    fun save(category: String, title: String, image: ByteArray?, messages: List<ChatMessage>): NotePath {
        val path = NoteNaming.uniquePath(title, category, isTaken = { notes.exists(it) })
        val folder = image?.let {
            val folder = NotePath.ATTACHMENTS_FOLDER_PREFIX + newFolderId()
            val target = path.resolveSibling("$folder/$IMAGE_NAME") ?: throw IllegalArgumentException("Bad folder $folder")
            notes.write(target, it)
            folder
        }
        notes.writeText(path, markdown(title.trim(), folder?.let { "$it/$IMAGE_NAME" }, messages), WritePrecondition.MustNotExist)
        return path
    }

    companion object {
        const val IMAGE_NAME = "recorte.png"

        fun markdown(title: String, imageLink: String?, messages: List<ChatMessage>): String = buildString {
            append("# ").append(title).append('\n')
            if (imageLink != null) append('\n').append("![Recorte]($imageLink)").append('\n')
            for (message in messages) {
                val label = if (message.speaker == Speaker.USER) "**Tú:**" else "**IA:**"
                append('\n').append(label).append(' ').append(message.text.trim()).append('\n')
            }
        }
    }
}
