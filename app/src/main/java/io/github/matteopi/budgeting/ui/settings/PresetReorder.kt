package io.github.matteopi.budgeting.ui.settings

/**
 * Moves the id [id] one place earlier ([delta] = -1) or later ([delta] = +1) within [ids].
 * Returns null if [id] isn't in the list or the move would go out of bounds (already first/last).
 */
internal fun reorderMove(ids: List<Long>, id: Long, delta: Int): List<Long>? {
    val index = ids.indexOf(id)
    val newIndex = index + delta
    if (index < 0 || newIndex < 0 || newIndex >= ids.size) return null
    val result = ids.toMutableList()
    result.removeAt(index)
    result.add(newIndex, id)
    return result
}
