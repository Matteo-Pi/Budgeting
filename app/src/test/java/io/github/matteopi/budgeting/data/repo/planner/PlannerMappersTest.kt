package io.github.matteopi.budgeting.data.repo.planner

import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskCompletion
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.data.db.dao.TaskWithChecklist
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Planner entity ⇄ core model round trips (docs/PLAN.md §13.5). */
class PlannerMappersTest {

    private val essay = Task(
        id = 3, title = "EC101 essay", notes = "2,000 words", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.ASSIGNMENT,
        dueDate = LocalDate.of(2026, 10, 23), dueTime = LocalTime.of(17, 0), estimateMinutes = 360, priority = Priority.HIGH,
        moduleId = 1, tags = listOf("coursework", "big"), reminderMinutesBefore = 1440, location = "Library",
        createdAt = Instant.ofEpochMilli(1_000), sortOrder = 4,
        checklist = listOf(ChecklistItem("Plan"), ChecklistItem("Sources", done = true)),
    )

    @Test
    fun `a task survives the round trip with its checklist in order`() {
        val entity = essay.toEntity()
        assertEquals(1020, entity.dueMinute)
        assertEquals("coursework,big", entity.tags)
        val rows = essay.toChecklistEntities().reversed()
        assertEquals(essay, TaskWithChecklist(entity, rows).toModel())
    }

    @Test
    fun `repeats and their weekdays survive`() {
        val laundry = Task(id = 4, title = "Laundry", dueDate = LocalDate.of(2026, 10, 25),
            repeat = TaskRepeat(RepeatKind.WEEKDAYS, weekdays = setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY)))
        assertEquals(laundry, laundry.toEntity().toModel())
        val everyTwoWeeks = laundry.copy(repeat = TaskRepeat(RepeatKind.WEEKS, interval = 2))
        assertEquals(everyTwoWeeks, everyTwoWeeks.toEntity().toModel())
    }

    @Test
    fun `blank checklist lines, spaces and tags are tidied on the way in`() {
        val messy = Task(title = "  Pack  ", tags = listOf(" a ", "b,c", "a"), location = "  ",
            checklist = listOf(ChecklistItem(" Charger "), ChecklistItem("   "), ChecklistItem("Notes")))
        val entity = messy.toEntity()
        assertEquals("Pack", entity.title)
        assertEquals("a,b,c", entity.tags)
        assertNull(entity.location)
        assertEquals(listOf("Charger" to 0, "Notes" to 1), messy.toChecklistEntities().map { it.text to it.position })
    }

    @Test
    fun `unknown names from a newer version read as defaults`() {
        val odd = essay.toEntity().copy(kind = "PROJECT", priority = "URGENT", deadlineType = "THESIS", repeatKind = "MONTHLY", dueMinute = 2000)
        val task = odd.toModel()
        assertEquals(TaskKind.TASK, task.kind)
        assertEquals(Priority.NONE, task.priority)
        assertNull(task.deadlineType)
        assertNull(task.repeat)
        assertNull(task.dueTime, "a time outside the day is dropped")
    }

    @Test
    fun `modules keep a lower-case code key and a weight from 1 to 3`() {
        val module = Module(id = 2, code = " EC101 ", name = "Economics", colorArgb = 0xFF3F7CAC, weeklyTargetMinutes = 300, weight = 5)
        val entity = module.toEntity()
        assertEquals("EC101", entity.code)
        assertEquals("ec101", entity.codeKey)
        assertEquals(3, entity.weight)
        assertEquals(module.copy(code = "EC101", weight = 3), entity.toModel())
    }

    @Test
    fun `completions survive`() {
        val done = TaskCompletion(5, 3, LocalDate.of(2026, 10, 20), Instant.ofEpochMilli(9_000))
        assertEquals(done, done.toEntity().toModel())
        assertEquals(done.copy(dueDate = null), done.copy(dueDate = null).toEntity().toModel())
    }
}
