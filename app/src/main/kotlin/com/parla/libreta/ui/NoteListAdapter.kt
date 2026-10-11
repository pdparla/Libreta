package com.parla.libreta.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.parla.libreta.R
import com.parla.libreta.data.local.NoteSummary

class NoteListAdapter(private val context: Context) : BaseAdapter() {

    private var notes: List<NoteSummary> = emptyList()

    fun submit(notes: List<NoteSummary>) {
        this.notes = notes
        notifyDataSetChanged()
    }

    override fun getCount(): Int = notes.size

    override fun getItem(position: Int): NoteSummary = notes[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_note, parent, false)
        val note = notes[position]
        view.findViewById<TextView>(R.id.title).text = note.path.baseName
        view.findViewById<TextView>(R.id.subtitle).text = subtitleOf(note)
        return view
    }

    private fun subtitleOf(note: NoteSummary): String {
        val age = DateUtils.getRelativeTimeSpanString(
            note.modifiedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        val category = note.path.category
        return if (category.isEmpty()) age.toString() else context.getString(R.string.category_and_age, category, age)
    }
}
