package io.github.matteopi.budgeting.ui.importer

import io.github.matteopi.budgeting.core.importer.RowStatus

/**
 * The review step's checkbox rules (docs/PLAN.md §6.5): every row that is not already imported starts ticked,
 * a possible duplicate of a manual entry included, because the check step asks about it before anything is
 * saved (a row in a pair is decided by its answer there, not by its tick). An already-imported row can't be
 * selected at all (it's excluded before the user ever sees a checkbox for it).
 */
object ImportSelection {
    fun defaultSelected(status: RowStatus): Boolean = status != RowStatus.ALREADY_IMPORTED

    fun isSelectable(status: RowStatus): Boolean = status != RowStatus.ALREADY_IMPORTED
}
