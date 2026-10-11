package com.parla.libreta.core.note

/**
 * Attachments of a note: the `.attachments.<id>/` folders next to it that its Markdown links to, the way
 * Nextcloud Notes stores pasted images. Links are relative to the note's folder, so the attachments must follow
 * the note when it changes category, and go with it when it is deleted.
 */
object NoteAttachments {

    private val FOLDER_REFERENCE = Regex("""(?:^|[\s(\[<"'])(\.attachments\.[^/\s()\[\]<>"']+)/""")

    /** Attachment folder names (e.g. `.attachments.k3f9a2`) that [content] links to. */
    fun foldersReferencedBy(content: String): Set<String> =
        FOLDER_REFERENCE.findAll(content).map { it.groupValues[1] }.filter(NotePath::isAttachmentsFolder).toSet()

    /** Every file in [repository] inside the attachment folders that the note at [note] links to. */
    fun filesOf(repository: NoteRepository, note: NotePath, content: String): List<NoteEntry> {
        val prefixes = foldersReferencedBy(content).map { folder ->
            if (note.category.isEmpty()) "$folder/" else "${note.category}/$folder/"
        }
        if (prefixes.isEmpty()) return emptyList()
        return repository.list().filter { entry -> prefixes.any { entry.path.value.startsWith(it) } }
    }

    /** Where [attachment] of a note at [from] goes when the note moves to [to]'s folder. */
    fun relocated(attachment: NotePath, from: NotePath, to: NotePath): NotePath {
        val inFolder = attachment.value.removePrefix(if (from.category.isEmpty()) "" else "${from.category}/")
        return NotePath(if (to.category.isEmpty()) inFolder else "${to.category}/$inFolder")
    }
}
