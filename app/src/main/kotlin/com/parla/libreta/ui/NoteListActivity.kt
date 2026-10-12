package com.parla.libreta.ui

import android.app.ActionBar
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.core.note.NoteSearch
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.core.note.SearchHit
import com.parla.libreta.ui.ai.AiChatActivity
import com.parla.libreta.ui.ink.InkNoteActivity
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
    private var allNotes: List<ListedNote> = emptyList()
    private val syncListener: (SyncStatus) -> Unit = ::onSyncStatus
    private val transcriptionListener: (Int) -> Unit = { read -> if (read > 0) reload() }
    private var query = ""

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
            open(adapter.getItem(position))
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

    // singleTask: pressing Home brings the list back and closes an open editor, which saves on its way out.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // As the home screen there is nothing to go back to.
        if (keyCode == KeyEvent.KEYCODE_BACK && HomeMode.isHomeIntent(intent)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        container.sync.addListener(syncListener)
        container.inkTranscriber.addListener(transcriptionListener)
        showSyncStatus(container.sync.status)
        reload()
        // Read whatever handwriting changed, so that search finds it (docs/design/ai.md).
        container.inkTranscriber.request()
    }

    override fun onPause() {
        super.onPause()
        container.sync.removeListener(syncListener)
        container.inkTranscriber.removeListener(transcriptionListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, CategoryFilter.encode(filter))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.note_list, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                query = newText
                applyFilter()
                return true
            }

            override fun onQueryTextSubmit(text: String): Boolean {
                searchView.clearFocus()
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                query = ""
                applyFilter()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_ask -> {
            startActivity(AiChatActivity.intentForNotebook(this, filter.categoryForNewNotes))
            true
        }
        R.id.action_new_note -> {
            startActivity(NoteEditorActivity.intentForNew(this, filter.categoryForNewNotes))
            true
        }
        R.id.action_new_ink -> {
            startActivity(InkNoteActivity.intentForNew(this, filter.categoryForNewNotes))
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
        container.background.execute({ listNotes() }) { result ->
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
        val tags = allNotes.flatMap { it.tags }.distinct().sorted()
        filters = listOf(CategoryFilter.All, CategoryFilter.Only("")) + categories.map { CategoryFilter.Only(it) } + tags.map { CategoryFilter.Tag(it) }
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
        is CategoryFilter.Tag -> getString(R.string.category_tag, filter.tag)
    }

    /** The notes of the chosen category; while searching, only those that match, each with where. */
    private fun applyFilter() {
        val shown = allNotes.filter { filter.matches(it) }
        findViewById<TextView>(R.id.empty).text =
            if (query.isBlank()) getString(R.string.empty_list) else getString(R.string.search_nothing, query.trim())
        if (query.isBlank()) return adapter.submit(shown)
        val byPath = shown.associateBy { it.path }
        val hits = NoteSearch.search(shown.map { it.searchable }, query)
        adapter.submit(hits.mapNotNull { byPath[it.path] }, hits.associateBy { it.path })
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

    /**
     * Every note, newest first, marking the handwritten ones, with what search can find it by: the title, the text
     * and, for handwriting, what each page says. The index reads only the notes that changed since the last time.
     */
    private fun listNotes(): List<ListedNote> {
        val entries = container.notebookIndex.entries(container.notes.list()).associateBy { it.path }
        return container.notes.summaries().sortedByDescending { it.modifiedAt }.map { summary ->
            val entry = entries[summary.path] ?: run {
                // Created or changed between the two listings: read it now.
                val markdown = try {
                    container.notes.read(summary.path).text
                } catch (e: NoteStoreException) {
                    ""
                }
                container.notebookIndex.entry(summary.path, markdown)
            }
            ListedNote(summary, entry.isInk, entry.searchable, entry.tags)
        }
    }

    private fun open(note: ListedNote) {
        // A match in the handwriting opens the note at its page.
        val page = adapter.hitOf(note)?.takeIf { it.where == SearchHit.Where.PAGE }?.page ?: 0
        startActivity(
            if (note.isInk) InkNoteActivity.intent(this, note.path, page) else NoteEditorActivity.intent(this, note.path),
        )
    }

    private fun confirmDelete(note: ListedNote) {
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
