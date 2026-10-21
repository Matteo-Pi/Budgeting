package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.suggest.StudyContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the planner reads from the plans and the record (docs/PLAN.md §13.4), at Tue 20 Oct 2026 12:00. */
class StudyContextTest {
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20)
    private fun at(day: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(LocalDate.of(2026, 10, day).atTime(h, m), zone).toInstant()
    private val now = at(20, 12)
    private val study = 1L
    private val gym = 3L

    @Test
    fun `planned time counts from now, study per day counts plans and records, and reviews need a block that happened`() {
        val plans = listOf(
            PlanBlock(start = at(20, 11), end = at(20, 13), categoryId = study, taskId = 7, moduleId = 4),
            PlanBlock(start = at(22, 9), end = at(22, 10), categoryId = study, moduleId = 4),
            PlanBlock(start = at(27, 9), end = at(27, 10), categoryId = study, moduleId = 4, taskId = 7),
            PlanBlock(start = at(21, 18), end = at(21, 19), categoryId = gym, habitId = 2),
            PlanBlock(start = at(19, 9), end = at(19, 10), categoryId = study, topicId = 5),
            PlanBlock(start = at(18, 9), end = at(18, 10), categoryId = study, topicId = 5),
        )
        val records = listOf(
            RecordChunk(start = at(19, 9), end = at(19, 10), categoryId = study, moduleId = 4),
            RecordChunk(start = at(20, 9), end = at(20, 10, 30), categoryId = study, moduleId = 4),
            RecordChunk(start = at(18, 20), end = at(18, 21), categoryId = study, moduleId = 4),
        )
        val totals = StudyContext.totals(plans, records, setOf(study), now, today, zone)
        assertEquals(mapOf(7L to 120), totals.plannedByTask, "the hour left of today's block and next week's")
        assertEquals(mapOf(4L to 120), totals.plannedByModule, "this week only: today's hour left and Thursday's")
        assertEquals(mapOf(2L to 60), totals.plannedByHabit)
        assertEquals(mapOf(4L to 150), totals.studiedByModule, "Monday and Tuesday; Sunday was last week")
        assertEquals(mapOf(today to 150, LocalDate.of(2026, 10, 22) to 60, LocalDate.of(2026, 10, 27) to 60, LocalDate.of(2026, 10, 19) to 60,
            LocalDate.of(2026, 10, 18) to 60), totals.usedByDay)
        assertEquals(mapOf(5L to listOf(LocalDate.of(2026, 10, 19))), totals.topicReviews, "Sunday's block wasn't studied")
    }
}
