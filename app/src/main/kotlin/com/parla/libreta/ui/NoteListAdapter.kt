package com.parla.libreta.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.parla.libreta.R
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.SearchHit
import com.parla.libreta.core.note.SearchableNote
import com.parla.libreta.data.local.NoteSummary

/** A note as the list shows it, with what search can find it by (docs/11-busqueda.md). */
data class ListedNote(val summary: NoteSummary, val isInk: Boolean, val searchable: SearchableNote, val tags: List<String> = emptyList()) {
    val path get() = summary.path
}

class NoteListAdapter(private val context: Context) : BaseAdapter() {

    private var notes: List<ListedNote> = emptyList()
    private var hits: Map<NotePath, SearchHit> = emptyMap()
    private val handwritten = context.getString(R.string.ink_handwritten)

    private class Holder(val title: TextView, val subtitle: TextView)

    /** The notes to show; with [hits] (a search, by the path of the note), each one shows where the query was found instead of its age. */
    fun submit(notes: List<ListedNote>, hits: Map<NotePath, SearchHit> = emptyMap()) {
        this.notes = notes
        this.hits = hits
        notifyDataSetChanged()
    }

    fun hitOf(note: ListedNote): SearchHit? = hits[note.path]

    override fun getCount(): Int = notes.size

    override fun getItem(position: Int): ListedNote = notes[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_note, parent, false).also {
            it.tag = Holder(it.findViewById(R.id.title), it.findViewById(R.id.subtitle))
        }
        val holder = view.tag as Holder
        val note = notes[position]
        holder.title.text = note.path.baseName
        holder.subtitle.text = subtitleOf(note)
        return view
    }

    private fun subtitleOf(note: ListedNote): String {
        hits[note.path]?.let { hit ->
            val page = if (hit.where == SearchHit.Where.PAGE) context.getString(R.string.search_page, hit.page + 1) else null
            return listOfNotNull(
                handwritten.takeIf { note.isInk },
                note.path.category.ifEmpty { null },
                page,
                hit.snippet,
            ).joinToString(" · ")
        }
        val age = DateUtils.getRelativeTimeSpanString(
            note.summary.modifiedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        val parts = listOfNotNull(
            handwritten.takeIf { note.isInk },
            note.path.category.ifEmpty { null },
            age.toString(),
        )
        return parts.joinToString(" · ")
    }
}
