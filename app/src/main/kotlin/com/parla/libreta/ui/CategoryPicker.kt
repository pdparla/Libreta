package com.parla.libreta.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import com.parla.libreta.R
import com.parla.libreta.container

/** Dialog to choose a note's category: "no category", the existing ones, or a new one typed in. */
object CategoryPicker {

    fun show(activity: Activity, current: String, onChosen: (String) -> Unit) {
        // A directory walk without reading any note: fast enough for the main thread.
        val categories = activity.container.notes.summaries()
            .map { it.path.category }.filter { it.isNotEmpty() }.distinct().sorted()
        val labels = listOf(activity.getString(R.string.category_none)) + categories + activity.getString(R.string.category_new)
        AlertDialog.Builder(activity)
            .setTitle(R.string.action_category)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onChosen("")
                    labels.lastIndex -> askNew(activity, current, onChosen)
                    else -> onChosen(categories[which - 1])
                }
            }
            .show()
    }

    private fun askNew(activity: Activity, current: String, onChosen: (String) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = activity.getString(R.string.category_hint)
            setText(current)
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.category_new)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> onChosen(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
