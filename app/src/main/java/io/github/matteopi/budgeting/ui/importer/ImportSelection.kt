package io.github.matteopi.budgeting.ui.importer

import io.github.matteopi.budgeting.core.importer.RowStatus

/**
 * The review step's checkbox rules (docs/PLAN.md §6.5): a new row starts ticked, a possible
 * duplicate of a manual entry starts unticked, and an already-imported row can't be selected at
 * all (it's excluded before the user ever sees a checkbox for it).
 */
object ImportSelection {
    fun defaultSelected(status: RowStatus): Boolean = status == RowStatus.NEW

    fun isSelectable(status: RowStatus): Boolean = status != RowStatus.ALREADY_IMPORTED
}
