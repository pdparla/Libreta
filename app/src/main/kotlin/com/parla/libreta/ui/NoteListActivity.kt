package com.parla.libreta.ui

import android.app.ActionBar
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.data.local.NoteSummary
import com.parla.libreta.sync.SyncService
import com.parla.libreta.sync.SyncStatus

/** Home screen: notes of the chosen category, most recently modified first, and the sync status. */
@Suppress("DEPRECATION") // ActionBar list navigation and window progress: deprecated on API 21+, current on API 19.
class NoteListActivity : Activity() {

    private lateinit var adapter: NoteListAdapter
    private lateinit var statusView: TextView
    private lateinit var categoryAdapter: ArrayAdapter<String>
    private var filters: List<CategoryFilter> = listOf(CategoryFilter.All)
    private var filter: CategoryFilter = CategoryFilter.All
    private var allNotes: List<NoteSummary> = emptyList()
    private val syncListener: (SyncStatus) -> Unit = ::onSyncStatus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
        setContentView(R.layout.activity_note_list)
        filter = CategoryFilter.decode(savedInstanceState?.getString(STATE_FILTER))

        adapter = NoteListAdapter(this)
        statusView = findViewById(R.id.sync_status)
        val list = findViewById<ListView>(R.id.notes)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(NoteEditorActivity.intent(this, adapter.getItem(position).path))
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(adapter.getItem(position))
            true
        }

        val bar = actionBar!!
        categoryAdapter = ArrayAdapter(bar.themedContext, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>())
        bar.setDisplayShowTitleEnabled(false)
        bar.navigationMode = ActionBar.NAVIGATION_MODE_LIST
        bar.setListNavigationCallbacks(categoryAdapter) { position, _ ->
            filter = filters[position]
            applyFilter()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        container.sync.addListener(syncListener)
        showSyncStatus(container.sync.status)
        reload()
    }

    override fun onPause() {
        super.onPause()
        container.sync.removeListener(syncListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, CategoryFilter.encode(filter))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.note_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_new_note -> {
            startActivity(NoteEditorActivity.intentForNew(this, filter.categoryForNewNotes))
            true
        }
        R.id.action_sync -> {
            SyncService.request(this)
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun reload() {
        container.background.execute({ container.notes.summaries().sortedByDescending { it.modifiedAt } }) { result ->
            if (isDestroyed) return@execute
            result
                .onSuccess { notes ->
                    allNotes = notes
                    showCategories()
                    applyFilter()
                }
                .onFailure { Toast.makeText(this, R.string.error_loading, Toast.LENGTH_LONG).show() }
        }
    }

    /** "All notes", "No category", then every category present, keeping the current choice if it still exists. */
    private fun showCategories() {
        val categories = allNotes.map { it.path.category }.filter { it.isNotEmpty() }.distinct().sorted()
        filters = listOf(CategoryFilter.All, CategoryFilter.Only("")) + categories.map { CategoryFilter.Only(it) }
        if (filter !in filters) filter = CategoryFilter.All

        categoryAdapter.setNotifyOnChange(false)
        categoryAdapter.clear()
        categoryAdapter.addAll(filters.map(::labelOf))
        categoryAdapter.notifyDataSetChanged()
        actionBar!!.setSelectedNavigationItem(filters.indexOf(filter))
    }

    private fun labelOf(filter: CategoryFilter): String = when (filter) {
        CategoryFilter.All -> getString(R.string.category_all)
        is CategoryFilter.Only -> filter.category.ifEmpty { getString(R.string.category_none) }
    }

    private fun applyFilter() {
        adapter.submit(allNotes.filter(filter::matches))
    }

    private fun onSyncStatus(status: SyncStatus) {
        showSyncStatus(status)
        if (status !is SyncStatus.Finished) return
        reload()
        val conflicts = status.report.conflicts.size
        if (conflicts > 0) {
            val message = resources.getQuantityString(R.plurals.sync_conflicts, conflicts, conflicts)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSyncStatus(status: SyncStatus) {
        setProgressBarIndeterminateVisibility(status is SyncStatus.Running)
        val text = SyncMessages.status(this, status)
        statusView.text = text
        statusView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun confirmDelete(note: NoteSummary) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, note.path.baseName))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                container.background.execute({ container.noteService.delete(note.path) }) { reload() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val STATE_FILTER = "filter"
    }
}
