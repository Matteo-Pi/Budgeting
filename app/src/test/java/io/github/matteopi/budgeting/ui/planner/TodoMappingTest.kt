package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskCompletion
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.todo.TaskQuickAdd
import io.github.matteopi.budgeting.core.planner.todo.TodoFilter
import io.github.matteopi.budgeting.data.repo.planner.DoneItem
import io.github.matteopi.budgeting.ui.planner.todo.TodoMapping
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The To-do tab's rows, sections and quick-add preview (docs/PLAN.md §13.9), on Tue 20 Oct 2026. */
class TodoMappingTest {

    private val today = LocalDate.of(2026, 10, 20)
    private val zone = ZoneId.of("Europe/London")
    private val ec101 = Module(id = 1, code = "EC101", name = "Economics", colorArgb = 0xFF3F7CAC)
    private val modules = listOf(ec101)

    private val essay = Task(id = 10, title = "EC101 essay", kind = TaskKind.DEADLINE, dueDate = LocalDate.of(2026, 10, 23),
        estimateMinutes = 360, moduleId = 1)
    private val research = Task(id = 11, title = "Research", parentId = 10, dueDate = today, estimateMinutes = 120, moduleId = 1,
        checklist = listOf(ChecklistItem("Library", done = true), ChecklistItem("Journals")))
    private val laundry = Task(id = 12, title = "Laundry", dueDate = LocalDate.of(2026, 10, 18), repeat = TaskRepeat(RepeatKind.WEEKS),
        tags = listOf("home"), priority = Priority.HIGH)
    private val call = Task(id = 13, title = "Call home", dueDate = today, dueTime = LocalTime.of(18, 30))
    private val someday = Task(id = 14, title = "Clean bike")
    private val tasks = listOf(essay, research, laundry, call, someday)

    @Test
    fun `today shows the overdue first, then what is due today`() {
        val sections = TodoMapping.sections(TodoFilter.TODAY, tasks, modules, today)
        assertEquals(listOf("Overdue", "Today"), sections.map { it.title })
        assertEquals(listOf(12L), sections[0].rows.map { it.id })
        assertEquals(listOf(13L, 11L), sections[1].rows.map { it.id }, "timed first")
    }

    @Test
    fun `a row's subtitle says what matters about it`() {
        val rows = TodoMapping.sections(TodoFilter.TODAY, tasks, modules, today).flatMap { it.rows }.associateBy { it.id }
        assertEquals("Step of EC101 essay · Due today · EC101 · 2 h · 1/2 done", rows.getValue(11).subtitle)
        assertEquals(0xFF3F7CAC, rows.getValue(11).moduleColor)
        assertEquals("Overdue by 2 days · Every week · #home", rows.getValue(12).subtitle)
        assertTrue(rows.getValue(12).overdue && rows.getValue(12).repeats && rows.getValue(12).highPriority)
        assertEquals("Due today at 18:30", rows.getValue(13).subtitle)
    }

    @Test
    fun `a deadline's row gives its work left, steps and countdown`() {
        val row = TodoMapping.sections(TodoFilter.UPCOMING, tasks, modules, today).flatMap { it.rows }.single { it.id == 10L }
        assertTrue(row.isDeadline)
        assertEquals("Due Friday · EC101", row.subtitle, "a deadline's estimate is in its status, not its subtitle")
        assertEquals("2 h left · 1 step left · due in 3 days", row.status, "the open step's estimate is the work left")
    }

    @Test
    fun `the other filters`() {
        assertEquals(listOf("Friday"), TodoMapping.sections(TodoFilter.UPCOMING, tasks, modules, today).map { it.title })
        assertEquals(listOf("Overdue"), TodoMapping.sections(TodoFilter.OVERDUE, tasks, modules, today).map { it.title })
        assertEquals(listOf(14L), TodoMapping.sections(TodoFilter.NO_DATE, tasks, modules, today).single().rows.map { it.id })
        val byModule = TodoMapping.sections(TodoFilter.BY_MODULE, tasks, modules, today)
        assertEquals(listOf("EC101 Economics", "No module"), byModule.map { it.title })
        assertEquals(listOf(11L, 10L), byModule[0].rows.map { it.id })
        assertTrue(TodoMapping.sections(TodoFilter.DONE, tasks, modules, today).isEmpty())
        assertTrue(TodoMapping.sections(TodoFilter.TODAY, emptyList(), modules, today).isEmpty())
    }

    @Test
    fun `the quick-add preview and the task it saves`() {
        val q = TaskQuickAdd.parse("Econ essay draft fri 3pm 2h #EC101 !!! #group", today, modules)
        assertEquals(listOf("Fri 23 Oct", "15:00", "2 h", "EC101", "High priority", "#group"), TodoMapping.previewChips(q, modules, today))
        val task = TodoMapping.taskFrom(q)
        assertEquals("Econ essay draft", task.title)
        assertEquals(LocalDate.of(2026, 10, 23), task.dueDate)
        assertEquals(1L, task.moduleId)
        assertEquals(listOf("group"), task.tags)
        assertTrue(TodoMapping.previewChips(TaskQuickAdd.parse("Just a title", today, modules), modules, today).isEmpty())
    }

    @Test
    fun `done log rows`() {
        val at = ZonedDateTime.of(2026, 10, 20, 14, 5, 0, 0, zone).toInstant()
        val row = TodoMapping.doneRow(DoneItem(TaskCompletion(7, 11, today, at), "Research", false, 1), modules, today, zone)
        assertEquals("Done today at 14:05 · EC101", row.subtitle)
        assertEquals(11L, row.taskId)
        val older = TodoMapping.doneRow(DoneItem(TaskCompletion(8, 12, null, at.minusSeconds(2 * 86_400)), "Laundry", false, null), modules, today, zone)
        assertEquals("Done Sun 18 Oct at 14:05", older.subtitle)
        val unknownModule = TodoMapping.doneRow(DoneItem(TaskCompletion(9, 1, null, Instant.EPOCH), "x", false, 99), modules, today, zone)
        assertTrue("·" !in unknownModule.subtitle, "a module that no longer exists is left out")
    }

    @Test
    fun `every filter has a label and an empty message`() {
        TodoMapping.FILTERS.forEach {
            assertTrue(TodoMapping.filterLabel(it).isNotBlank())
            assertTrue(TodoMapping.emptyMessage(it).isNotBlank())
        }
        assertEquals(TodoFilter.entries.toSet(), TodoMapping.FILTERS.toSet())
    }
}
