package io.github.matteopi.budgeting.ui.plan

import io.github.matteopi.budgeting.core.model.TermPeriod
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermValidationTest {
    private val autumn = TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2026, 9, 28), endDate = LocalDate.of(2026, 12, 12))

    @Test
    fun endBeforeStartIsInvalid() {
        assertTrue(TermValidation.endBeforeStart(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 1)))
        assertFalse(TermValidation.endBeforeStart(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)))
        assertFalse(TermValidation.endBeforeStart(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun detectsOverlappingRanges() {
        val overlapping = autumn.copy(id = 2, startDate = LocalDate.of(2026, 12, 1), endDate = LocalDate.of(2027, 1, 15))
        assertTrue(TermValidation.overlaps(autumn, overlapping))
    }

    @Test
    fun adjacentRangesDoNotOverlap() {
        val spring = autumn.copy(id = 2, startDate = autumn.endDate.plusDays(1), endDate = LocalDate.of(2027, 3, 1))
        assertFalse(TermValidation.overlaps(autumn, spring))
    }

    @Test
    fun findOverlapsExcludesTheTermBeingEdited() {
        val existing = listOf(autumn)
        // Editing "autumn" itself to the same range should not be reported as overlapping itself.
        val result = TermValidation.findOverlaps(autumn.startDate, autumn.endDate, excludingId = autumn.id, existing = existing)
        assertEquals(emptyList(), result)
    }

    @Test
    fun findOverlapsReportsOthers() {
        val existing = listOf(autumn)
        val result = TermValidation.findOverlaps(
            start = LocalDate.of(2026, 12, 1),
            end = LocalDate.of(2027, 1, 1),
            excludingId = null,
            existing = existing,
        )
        assertEquals(listOf(autumn), result)
    }
}
