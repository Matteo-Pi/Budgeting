package io.github.matteopi.budgeting.ui.importer

import io.github.matteopi.budgeting.core.importer.RowStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportSelectionTest {
    @Test
    fun `new rows are selected by default`() {
        assertTrue(ImportSelection.defaultSelected(RowStatus.NEW))
    }

    @Test
    fun `possible duplicates are unselected by default`() {
        assertFalse(ImportSelection.defaultSelected(RowStatus.POSSIBLE_DUPLICATE))
    }

    @Test
    fun `already imported rows are unselected by default`() {
        assertFalse(ImportSelection.defaultSelected(RowStatus.ALREADY_IMPORTED))
    }

    @Test
    fun `new and possible duplicate rows are selectable`() {
        assertTrue(ImportSelection.isSelectable(RowStatus.NEW))
        assertTrue(ImportSelection.isSelectable(RowStatus.POSSIBLE_DUPLICATE))
    }

    @Test
    fun `already imported rows are never selectable`() {
        assertFalse(ImportSelection.isSelectable(RowStatus.ALREADY_IMPORTED))
    }
}
