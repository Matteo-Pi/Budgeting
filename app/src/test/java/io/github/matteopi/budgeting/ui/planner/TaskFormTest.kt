package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.todo.TaskField
import io.github.matteopi.budgeting.core.planner.todo.TaskQuickAdd
import io.github.matteopi.budgeting.ui.planner.task.TaskForm
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The task editor's form (docs/PLAN.md §13.9): what it saves and what stops it. */
class TaskFormTest {

    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun `a task survives being opened and saved unchanged`() {
        val task = Task(
            id = 4, title = "Laundry", notes = "Darks", dueDate = today, dueTime = LocalTime.of(10, 0), estimateMinutes = 30,
            priority = Priority.LOW, moduleId = 2, tags = listOf("home"), repeat = TaskRepeat(RepeatKind.WEEKDAYS, weekdays = setOf(DayOfWeek.SUNDAY)),
            reminderMinutesBefore = 15, location = "Halls", createdAt = Instant.ofEpochMilli(5), sortOrder = 3,
            checklist = listOf(ChecklistItem("Whites", done = true)),
        )
        assertEquals(task, TaskForm.from(task).toTask())
        val exam = Task(id = 5, title = "MA102 exam", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.EXAM, dueDate = today,
            dueTime = LocalTime.of(9, 30), lengthMinutes = 120, location = "Sports hall")
        assertEquals(exam, TaskForm.from(exam).toTask())
    }

    @Test
    fun `only what the kind allows is saved`() {
        val deadline = TaskForm(title = "Essay", kind = TaskKind.DEADLINE, dueDate = today, repeatKind = RepeatKind.DAYS, lengthMinutes = 60)
        val saved = deadline.toTask()
        assertNull(saved.repeat, "deadlines don't repeat")
        assertNull(saved.lengthMinutes, "only exams have a length")
        assertEquals(DeadlineType.ASSIGNMENT, saved.deadlineType)
        assertNull(TaskForm(title = "x", deadlineType = DeadlineType.EXAM).toTask().deadlineType, "a plain task has no type")
        val step = TaskForm(title = "Draft", parentId = 9, kind = TaskKind.DEADLINE, repeatKind = RepeatKind.WEEKS)
        assertEquals(TaskKind.TASK, step.toTask().kind, "a step is always a task")
        assertNull(step.toTask().repeat)
        assertTrue(!step.canRepeat)
        val days = TaskForm(title = "x", dueDate = today, repeatKind = RepeatKind.DAYS, repeatDays = setOf(DayOfWeek.MONDAY), repeatInterval = 3)
        assertEquals(TaskRepeat(RepeatKind.DAYS, 3), days.toTask().repeat, "chosen days only for weekday repeats")
    }

    @Test
    fun `tags, checklists and spaces are tidied`() {
        val form = TaskForm(title = "  Pack ", tagsText = "#trip, home ,, trip", checklist = listOf(ChecklistItem(" Charger "), ChecklistItem("  ")))
        val task = form.toTask()
        assertEquals("Pack", task.title)
        assertEquals(listOf("trip", "home"), task.tags)
        assertEquals(listOf(ChecklistItem("Charger")), task.checklist)
    }

    @Test
    fun `errors come from the rules`() {
        assertEquals(setOf(TaskField.TITLE), TaskForm().errors.keys)
        assertEquals(setOf(TaskField.DUE_DATE), TaskForm(title = "x", kind = TaskKind.DEADLINE).errors.keys)
        assertTrue(TaskForm(title = "x").errors.isEmpty())
    }

    @Test
    fun `reminder choices follow whether the task has a time`() {
        assertEquals(listOf(null, 0, 1440), TaskForm(title = "x", dueDate = today).reminderChoices)
        assertEquals(listOf(null, 0, 15, 60, 1440), TaskForm(title = "x", dueDate = today, dueTime = LocalTime.NOON).reminderChoices)
        assertEquals(listOf(null, 0, 1440, 30), TaskForm(title = "x", dueDate = today, reminderMinutesBefore = 30).reminderChoices, "the current one is kept")
    }

    @Test
    fun `a quick-add line opens the editor filled in`() {
        val form = TaskForm.fromQuick(TaskQuickAdd.parse("tomorrow 3pm 2h !! #reading", today))
        assertEquals("", form.title)
        assertEquals(today.plusDays(1), form.dueDate)
        assertEquals(LocalTime.of(15, 0), form.dueTime)
        assertEquals(120, form.estimateMinutes)
        assertEquals(Priority.MEDIUM, form.priority)
        assertEquals("reading", form.tagsText)
    }
}
