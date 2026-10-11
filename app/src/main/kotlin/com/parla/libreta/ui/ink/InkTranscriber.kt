package com.parla.libreta.ui.ink

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.parla.libreta.core.ink.InkNote
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.ink.InkRect
import com.parla.libreta.core.ink.InkTranscriptService
import com.parla.libreta.core.ink.InkTranscripts
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.readOrNull
import com.parla.libreta.data.ai.AiAssistant
import com.parla.libreta.data.ai.TurnHandle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the handwriting of every handwritten note in the background, page by page, so that search finds it
 * (docs/11-busqueda.md). Only pages whose strokes changed since they were read are sent, one at a time, and each
 * result is stored at once, so an interrupted pass loses nothing. A pass stops quietly on the first error (no
 * session, no network, the plan's usage limit): what is left waits for the next one.
 */
class InkTranscriber(
    private val notes: NoteRepository,
    private val inkNotes: InkNoteService,
    private val transcripts: InkTranscriptService,
    private val ai: () -> AiAssistant,
    private val isSignedIn: () -> Boolean,
    private val preferences: SharedPreferences,
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val mainThread = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()

    /** "Transcribir lo escrito a mano para buscarlo" in Ajustes; on unless turned off. */
    var isEnabled: Boolean
        get() = preferences.getBoolean(PREF_ENABLED, true)
        set(value) = preferences.edit().putBoolean(PREF_ENABLED, value).apply()

    /** Called on the main thread after each pass, with how many pages it read. */
    fun addListener(listener: (Int) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners -= listener
    }

    /** Starts a pass, unless one is running, it is turned off or there is no ChatGPT session. */
    fun request() {
        if (!isEnabled || !isSignedIn()) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            var read = 0
            try {
                read = pass()
            } finally {
                running.set(false)
                val count = read
                mainThread.post { listeners.forEach { it(count) } }
            }
        }
    }

    /** Pages waiting to be read, in every handwritten note. Reads the notes: call it off the main thread. */
    fun countPending(): Int = handwrittenNotes().sumOf { transcripts.pending(it).size }

    private fun pass(): Int {
        var read = 0
        for (note in handwrittenNotes()) {
            for (index in transcripts.pending(note)) {
                val page = note.document.pages[index]
                val text = if (page.isEmpty) {
                    // Nothing to read: no need to ask the model.
                    ""
                } else {
                    try {
                        val image = InkRenderer.renderSelection(page, InkRect(0f, 0f, page.width.toFloat(), page.height.toFloat()))
                        ai().convertToText(image, TurnHandle())
                    } catch (e: Exception) {
                        // Counts and types only: what the notes say never goes to the log.
                        Log.i(TAG, "pass stopped after $read pages: ${e.javaClass.simpleName}")
                        return read
                    }
                }
                try {
                    transcripts.save(note, index, InkTranscripts.hashOf(page), text)
                } catch (e: Exception) {
                    Log.i(TAG, "pass stopped after $read pages: ${e.javaClass.simpleName}")
                    return read
                }
                if (!page.isEmpty) read++
            }
        }
        if (read > 0) Log.i(TAG, "pass read $read pages")
        return read
    }

    /** Every handwritten note, opened one at a time as the sequence is walked. */
    private fun handwrittenNotes(): Sequence<InkNote> =
        notes.list().asSequence().map { it.path }.filter { it.isNote }.mapNotNull { path ->
            try {
                val markdown = notes.readOrNull(path)?.text ?: return@mapNotNull null
                if (!inkNotes.isInkNote(path, markdown) { notes.readOrNull(it) != null }) null else inkNotes.open(path)
            } catch (e: Exception) {
                null
            }
        }

    private companion object {
        const val TAG = "LibretaSearch"
        const val PREF_ENABLED = "transcribe_handwriting"
    }
}
