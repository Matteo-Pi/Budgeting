package io.github.matteopi.budgeting.ui.planner.components

/** What a Planner screen says after a change, with the *Undo* that puts it back. */
class UndoableEvent(val message: String, val undo: suspend () -> Unit)
