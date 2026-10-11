package com.parla.libreta.ui

import com.parla.libreta.data.local.NoteSummary

/** Which notes the list shows. */
sealed class CategoryFilter {

    abstract fun matches(note: NoteSummary): Boolean

    data object All : CategoryFilter() {
        override fun matches(note: NoteSummary) = true
    }

    /** Notes directly in [category]; "" means notes without a category. Sub-categories have their own entry. */
    data class Only(val category: String) : CategoryFilter() {
        override fun matches(note: NoteSummary) = note.path.category == category
    }

    /** Category a new note created under this filter goes to. */
    val categoryForNewNotes: String get() = (this as? Only)?.category.orEmpty()

    companion object {
        private const val ALL = "\u0000all"

        fun encode(filter: CategoryFilter): String = (filter as? Only)?.category ?: ALL

        fun decode(value: String?): CategoryFilter = if (value == null || value == ALL) All else Only(value)
    }
}
