package com.parla.libreta.ui

/** Which notes the list shows: all, those of one category, or those with one tag (docs/13-indice.md). */
sealed class CategoryFilter {

    abstract fun matches(note: ListedNote): Boolean

    data object All : CategoryFilter() {
        override fun matches(note: ListedNote) = true
    }

    /** Notes directly in [category]; "" means notes without a category. Sub-categories have their own entry. */
    data class Only(val category: String) : CategoryFilter() {
        override fun matches(note: ListedNote) = note.path.category == category
    }

    /** Notes with [tag], in any category. */
    data class Tag(val tag: String) : CategoryFilter() {
        override fun matches(note: ListedNote) = tag in note.tags
    }

    /** Category a new note created under this filter goes to. */
    val categoryForNewNotes: String get() = (this as? Only)?.category.orEmpty()

    companion object {
        private const val ALL = "\u0000all"
        private const val TAG = "\u0000tag:"

        fun encode(filter: CategoryFilter): String = when (filter) {
            All -> ALL
            is Only -> filter.category
            is Tag -> TAG + filter.tag
        }

        fun decode(value: String?): CategoryFilter = when {
            value == null || value == ALL -> All
            value.startsWith(TAG) -> Tag(value.removePrefix(TAG))
            else -> Only(value)
        }
    }
}
