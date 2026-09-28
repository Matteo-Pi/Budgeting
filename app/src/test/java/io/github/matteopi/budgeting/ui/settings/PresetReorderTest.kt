package io.github.matteopi.budgeting.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresetReorderTest {
    @Test
    fun movesAnEntryUp() {
        assertEquals(listOf(2L, 1L, 3L), reorderMove(listOf(1L, 2L, 3L), id = 2L, delta = -1))
    }

    @Test
    fun movesAnEntryDown() {
        assertEquals(listOf(1L, 3L, 2L), reorderMove(listOf(1L, 2L, 3L), id = 2L, delta = 1))
    }

    @Test
    fun cannotMoveTheFirstEntryUp() {
        assertNull(reorderMove(listOf(1L, 2L, 3L), id = 1L, delta = -1))
    }

    @Test
    fun cannotMoveTheLastEntryDown() {
        assertNull(reorderMove(listOf(1L, 2L, 3L), id = 3L, delta = 1))
    }

    @Test
    fun unknownIdIsANoOp() {
        assertNull(reorderMove(listOf(1L, 2L, 3L), id = 99L, delta = 1))
    }
}
