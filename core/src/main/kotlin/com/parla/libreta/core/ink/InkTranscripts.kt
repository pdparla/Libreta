package com.parla.libreta.core.ink

import com.parla.libreta.core.note.ContentHash
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.note.WritePrecondition
import com.parla.libreta.core.note.readOrNull
import com.parla.libreta.core.note.writeText

/** The text read from one page, and the [hash] of the page it was read from. */
data class PageTranscript(val hash: String, val text: String)

/**
 * What the handwriting of a note says, page by page, for search (docs/design/ai.md). Stored next to the strokes as
 * `libreta.text.json`, so it syncs and follows the note:
 *
 * ```json
 * {"format":"libreta-text","version":1,"pages":[{"hash":"sha1:…","text":"Lista de la compra"}]}
 * ```
 */
object InkTranscripts {

    const val FILE = "libreta.text.json"
    const val FORMAT = "libreta-text"
    const val VERSION = 1

    /**
     * A fingerprint of everything that can change what a page says: its strokes, size and paper, not its marks:
     * marking a title must not read the page again (docs/design/ink.md).
     */
    fun hashOf(page: InkPage): String = ContentHash.of(InkJson.encode(InkDocument(listOf(page.copy(marks = emptyList()))))).value

    /** Indexes of the pages of [document] whose text is missing or was read from a different page. */
    fun pending(document: InkDocument, transcripts: List<PageTranscript>): List<Int> =
        document.pages.indices.filter { i -> transcripts.getOrNull(i)?.hash != hashOf(document.pages[i]) }

    fun encode(transcripts: List<PageTranscript>): String = buildString {
        append("""{"format":"$FORMAT","version":$VERSION,"pages":[""")
        transcripts.forEachIndexed { i, page ->
            if (i > 0) append(',')
            append("{\"hash\":").append(jsonString(page.hash)).append(",\"text\":").append(jsonString(page.text)).append('}')
        }
        append("]}")
    }

    /** @throws IllegalArgumentException if [json] is not a transcript file. */
    fun decode(json: String): List<PageTranscript> {
        val root = JsonReader(json).readDocument() as? Map<*, *> ?: throw IllegalArgumentException("Not a transcript file")
        require(root["format"] == FORMAT) { "Unknown transcript format ${root["format"]}" }
        return (root["pages"] as? List<*>).orEmpty().map { page ->
            val fields = page as? Map<*, *> ?: throw IllegalArgumentException("Transcript page is not an object")
            PageTranscript(fields["hash"] as? String ?: "", fields["text"] as? String ?: "")
        }
    }
}

/** Reads and writes the transcripts of handwritten notes in the local [NoteRepository]. */
class InkTranscriptService(private val notes: NoteRepository) {

    /** The note's transcripts; empty when there are none yet or the file is unreadable. */
    fun read(note: InkNote): List<PageTranscript> {
        val file = fileOf(note) ?: return emptyList()
        val text = notes.readOrNull(file)?.text ?: return emptyList()
        return try {
            InkTranscripts.decode(text)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    fun pending(note: InkNote): List<Int> = InkTranscripts.pending(note.document, read(note))

    /**
     * The transcripts of the handwritten note at [path] whose Markdown is [markdown], found through its attachment
     * folders without reading the strokes: for the search index, which reads every note.
     */
    fun readFor(path: NotePath, markdown: String): List<PageTranscript> {
        for (inkFile in InkNoteFormat.inkFileCandidates(markdown)) {
            val folder = InkNoteFormat.folderOf(inkFile) ?: continue
            val text = path.resolveSibling("$folder/${InkTranscripts.FILE}")?.let(notes::readOrNull)?.text ?: continue
            return try {
                InkTranscripts.decode(text)
            } catch (e: IllegalArgumentException) {
                emptyList()
            }
        }
        return emptyList()
    }

    /**
     * Stores [text] as what page [index] of [note] says, read from the page with [hash]. Merges with what is stored
     * (another page may have been saved meanwhile) and drops entries for pages the note no longer has.
     */
    fun save(note: InkNote, index: Int, hash: String, text: String) {
        val file = fileOf(note) ?: throw IllegalArgumentException("Bad folder ${note.folder}")
        repeat(MERGE_ATTEMPTS) {
            val current = notes.readOrNull(file)
            val stored = current?.let {
                try {
                    InkTranscripts.decode(it.text)
                } catch (e: IllegalArgumentException) {
                    emptyList()
                }
            }.orEmpty()
            val pages = (0 until note.document.pages.size).map { i -> stored.getOrNull(i) ?: PageTranscript("", "") }.toMutableList()
            pages[index] = PageTranscript(hash, text)
            val precondition = if (current == null) WritePrecondition.MustNotExist else WritePrecondition.MustMatch(current.version)
            try {
                notes.writeText(file, InkTranscripts.encode(pages), precondition)
                return
            } catch (e: PreconditionFailedException) {
                // Written meanwhile: read it again and merge once more.
            }
        }
        throw PreconditionFailedException(file)
    }

    private fun fileOf(note: InkNote) = note.path.resolveSibling("${note.folder}/${InkTranscripts.FILE}")

    private companion object {
        const val MERGE_ATTEMPTS = 3
    }
}
