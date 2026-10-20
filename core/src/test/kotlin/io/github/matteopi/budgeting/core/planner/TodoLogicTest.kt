package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.model.Weekdays
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import io.github.matteopi.budgeting.core.planner.todo.DeadlineState
import io.github.matteopi.budgeting.core.planner.todo.DeadlineStatus
import io.github.matteopi.budgeting.core.planner.todo.TaskField
import io.github.matteopi.budgeting.core.planner.todo.TaskRepeats
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import io.github.matteopi.budgeting.core.planner.todo.TaskViews
import io.github.matteopi.budgeting.core.planner.todo.TemplateStep
import io.github.matteopi.budgeting.core.planner.todo.TodoFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Repeats, the To-do views, the rules, deadlines and templates (docs/PLAN.md §13.4), on Tue 20 Oct 2026. */
class TodoLogicTest {

    private val today = LocalDate.of(2026, 10, 20)
    private fun d(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)

    // ---- Repeats -----------------------------------------------------------------------------------

    @Test
    fun `days and weeks step on from the due date past the day it was done`() {
        val daily = TaskRepeat(RepeatKind.DAYS)
        assertEquals(d(10, 21), TaskRepeats.next(today, daily, completedOn = today))
        assertEquals(d(10, 24), TaskRepeats.next(today, daily, completedOn = d(10, 23)), "done 3 days late: tomorrow, not the missed days")
        assertEquals(d(10, 21), TaskRepeats.next(today, daily, completedOn = d(10, 18)), "done early: one step on")
        val everyThree = TaskRepeat(RepeatKind.DAYS, interval = 3)
        assertEquals(d(10, 23), TaskRepeats.next(today, everyThree, completedOn = today))
        assertEquals(d(10, 26), TaskRepeats.next(today, everyThree, completedOn = d(10, 23)), "on the rule's own days")
        val weekly = TaskRepeat(RepeatKind.WEEKS)
        assertEquals(d(10, 25), TaskRepeats.next(d(10, 18), weekly, completedOn = d(10, 20)), "Sunday laundry done on Tuesday: next Sunday")
        assertEquals(d(11, 1), TaskRepeats.next(d(10, 18), weekly, completedOn = d(10, 25)), "exactly a week late: the week after")
        assertEquals(d(11, 3), TaskRepeats.next(today, TaskRepeat(RepeatKind.WEEKS, interval = 2), completedOn = today))
        assertEquals(d(1, 1, 2027), TaskRepeats.next(d(12, 31), daily, completedOn = d(12, 31)), "across the year")
    }

    @Test
    fun `chosen weekdays give the next chosen day`() {
        val monWedFri = TaskRepeat(RepeatKind.WEEKDAYS, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        assertEquals(d(10, 21), TaskRepeats.next(d(10, 19), monWedFri, completedOn = today))
        assertEquals(d(10, 23), TaskRepeats.next(d(10, 21), monWedFri, completedOn = d(10, 21)))
        assertEquals(d(10, 26), TaskRepeats.next(d(10, 23), monWedFri, completedOn = d(10, 24)))
        assertEquals(d(10, 27), TaskRepeats.next(today, TaskRepeat(RepeatKind.WEEKDAYS, weekdays = setOf(DayOfWeek.TUESDAY)), today))
        assertEquals(d(10, 27), TaskRepeats.next(today, TaskRepeat(RepeatKind.WEEKDAYS), today), "no days (old data): a week on")
    }

    @Test
    fun `weekday sets round trip through their bits`() {
        assertEquals(0, Weekdays.toBits(emptySet()))
        assertEquals(1, Weekdays.toBits(setOf(DayOfWeek.MONDAY)))
        assertEquals(64, Weekdays.toBits(setOf(DayOfWeek.SUNDAY)))
        for (bits in 0 until 128) assertEquals(bits, Weekdays.toBits(Weekdays.fromBits(bits)))
    }

    // ---- Views -------------------------------------------------------------------------------------

    private fun task(id: Long, due: LocalDate?, time: LocalTime? = null, priority: Priority = Priority.NONE, module: Long? = null, done: Boolean = false) =
        Task(
            id = id, title = "T$id", dueDate = due, dueTime = time, priority = priority, moduleId = module,
            completedAt = if (done) Instant.ofEpochSecond(id) else null, sortOrder = id.toInt(),
        )

    private val tasks = listOf(
        task(1, today),
        task(2, today, LocalTime.of(15, 0)),
        task(3, today, priority = Priority.HIGH),
        task(4, today, LocalTime.of(9, 0)),
        task(5, d(10, 18)),
        task(6, d(10, 19), priority = Priority.HIGH),
        task(7, d(10, 21), module = 1),
        task(8, d(11, 3)),
        task(9, d(11, 4)),
        task(10, d(11, 12), module = 2),
        task(11, null, module = 1),
        task(12, today, done = true),
        task(13, d(10, 23), module = 99),
    )

    @Test
    fun `today, overdue and no date`() {
        assertEquals(listOf(4L, 2L, 3L, 1L), TaskViews.today(tasks, today).map { it.id }, "timed first, then priority, then order")
        assertEquals(listOf(5L, 6L), TaskViews.overdue(tasks, today).map { it.id }, "oldest first")
        assertEquals(listOf(11L), TaskViews.noDate(tasks).map { it.id })
    }

    @Test
    fun `upcoming by day for this week and next, then by week`() {
        val groups = TaskViews.upcoming(tasks + task(14, d(11, 1)) + task(15, d(10, 27)), today)
        assertEquals(listOf("Tomorrow", "Friday", "Tue 27 Oct", "Sun 1 Nov", "Week of 2 Nov", "Week of 9 Nov"), groups.map { it.title })
        assertEquals(
            listOf(listOf(7L), listOf(13L), listOf(15L), listOf(14L), listOf(8L, 9L), listOf(10L)),
            groups.map { g -> g.tasks.map { it.id } },
        )
    }

    @Test
    fun `by module in the modules' order then no module`() {
        val modules = listOf(Module(id = 2, code = "MA102", sortOrder = 0), Module(id = 1, code = "EC101", name = "Economics", sortOrder = 1))
        val groups = TaskViews.byModule(tasks, modules)
        assertEquals(listOf("MA102", "EC101 Economics", "No module"), groups.map { it.title })
        assertEquals(listOf(7L, 11L), groups[1].tasks.map { it.id }, "dated first")
        assertTrue(13L in groups[2].tasks.map { it.id }, "a missing module counts as none")
    }

    @Test
    fun `counts leave out done tasks`() {
        val counts = TaskViews.counts(tasks, today)
        assertEquals(4, counts[TodoFilter.TODAY])
        assertEquals(2, counts[TodoFilter.OVERDUE])
        assertEquals(5, counts[TodoFilter.UPCOMING])
        assertEquals(1, counts[TodoFilter.NO_DATE])
    }

    // ---- Rules -------------------------------------------------------------------------------------

    @Test
    fun `what a task needs`() {
        val ok = Task(title = "Essay", dueDate = today)
        assertTrue(TaskRules.isValid(ok))
        assertEquals(setOf(TaskField.TITLE), TaskRules.errors(ok.copy(title = "  ")).keys)
        assertEquals(setOf(TaskField.DUE_DATE), TaskRules.errors(Task(title = "x", dueTime = LocalTime.NOON)).keys)
        assertEquals(setOf(TaskField.DUE_DATE), TaskRules.errors(Task(title = "x", kind = TaskKind.DEADLINE)).keys)
        assertEquals(setOf(TaskField.ESTIMATE), TaskRules.errors(ok.copy(estimateMinutes = 4)).keys)
        assertTrue(TaskRules.isValid(ok.copy(estimateMinutes = 5)))
        assertEquals(setOf(TaskField.ESTIMATE), TaskRules.errors(ok.copy(estimateMinutes = 12_001)).keys)
        assertEquals(setOf(TaskField.LENGTH), TaskRules.errors(ok.copy(lengthMinutes = 120)).keys, "a length needs a time")
        assertTrue(TaskRules.isValid(ok.copy(kind = TaskKind.DEADLINE, deadlineType = DeadlineType.EXAM, dueTime = LocalTime.of(9, 30), lengthMinutes = 120)))
        assertEquals(setOf(TaskField.REPEAT), TaskRules.errors(Task(title = "x", repeat = TaskRepeat(RepeatKind.DAYS))).keys)
        assertEquals(setOf(TaskField.REPEAT), TaskRules.errors(ok.copy(repeat = TaskRepeat(RepeatKind.WEEKDAYS))).keys)
        assertEquals(setOf(TaskField.REPEAT), TaskRules.errors(ok.copy(repeat = TaskRepeat(RepeatKind.DAYS, interval = 0))).keys)
        assertEquals(setOf(TaskField.REPEAT), TaskRules.errors(ok.copy(kind = TaskKind.DEADLINE, repeat = TaskRepeat(RepeatKind.DAYS))).keys)
        assertEquals(setOf(TaskField.REMINDER), TaskRules.errors(Task(title = "x", reminderMinutesBefore = 0)).keys)
        assertEquals(setOf(TaskField.DUE_DATE), TaskRules.errors(ok.copy(deadlineType = DeadlineType.EXAM)).keys, "only deadlines have a type")
    }

    @Test
    fun `module codes are unique without case`() {
        val modules = listOf(Module(id = 1, code = "EC101"))
        assertFalse(TaskRules.moduleCodeFree("ec101 ", modules))
        assertTrue(TaskRules.moduleCodeFree("EC101", modules, exceptId = 1))
        assertTrue(TaskRules.moduleCodeFree("EC102", modules))
    }

    // ---- Deadlines ---------------------------------------------------------------------------------

    private val essay = Task(id = 1, title = "EC101 essay", kind = TaskKind.DEADLINE, dueDate = d(10, 23), estimateMinutes = 360)

    @Test
    fun `work left from the deadline's own estimate`() {
        val p = DeadlineStatus.of(essay, emptyList(), mapOf(1L to 60), today)
        assertEquals(DeadlineState.IN_PROGRESS, p.state, "no free time is known yet")
        assertEquals(300, p.workLeftMinutes)
        assertEquals(360, p.estimateMinutes)
        assertEquals(3L, p.daysLeft)
        assertEquals(0, DeadlineStatus.of(essay, emptyList(), mapOf(1L to 500), today).workLeftMinutes, "never below zero")
    }

    @Test
    fun `work left from the steps' estimates`() {
        val steps = listOf(
            Task(id = 2, title = "Research", parentId = 1, estimateMinutes = 120, completedAt = Instant.EPOCH),
            Task(id = 3, title = "Draft", parentId = 1, estimateMinutes = 180),
            Task(id = 4, title = "Edit", parentId = 1, estimateMinutes = 60),
            Task(id = 5, title = "Print", parentId = 1),
        )
        val p = DeadlineStatus.of(essay, steps, mapOf(3L to 30, 2L to 200), today)
        assertEquals(360, p.estimateMinutes, "the steps' sum")
        assertEquals(150 + 60, p.workLeftMinutes, "open steps only, less what was studied on them")
        assertEquals(1, p.stepsDone)
        assertEquals(4, p.stepsTotal)
        assertEquals(230, p.studiedMinutes)
    }

    @Test
    fun `the states`() {
        assertEquals(DeadlineState.DONE, DeadlineStatus.of(essay.copy(completedAt = Instant.EPOCH), emptyList(), emptyMap(), today).state)
        assertEquals(DeadlineState.OVERDUE, DeadlineStatus.of(essay.copy(dueDate = d(10, 19)), emptyList(), emptyMap(), today).state)
        assertEquals(DeadlineState.NO_ESTIMATE, DeadlineStatus.of(essay.copy(estimateMinutes = null), emptyList(), emptyMap(), today).state)
        fun withFree(free: Int) = DeadlineStatus.of(essay, emptyList(), emptyMap(), today, freeMinutesBefore = free).state
        assertEquals(DeadlineState.ON_TRACK, withFree(720), "6 h left in 12 h free is half")
        assertEquals(DeadlineState.TIGHT, withFree(540), "6 h left, 9 h free")
        assertEquals(DeadlineState.TIGHT, withFree(360))
        assertEquals(DeadlineState.AT_RISK, withFree(359))
        assertEquals(DeadlineState.ON_TRACK, DeadlineStatus.of(essay, emptyList(), mapOf(1L to 360), today, 0).state, "nothing left")
    }

    // ---- Templates ---------------------------------------------------------------------------------

    @Test
    fun `the essay template spreads its steps up to the deadline`() {
        val deadline = essay.copy(dueDate = d(10, 30), moduleId = 7) // 10 days away
        val steps = BreakdownTemplates.apply(BreakdownTemplates.defaults.first(), deadline, today)
        assertEquals(listOf("Research", "Outline", "First draft", "Edit", "References and submit"), steps.map { it.title })
        // Shares 30, 40, 80, 95 and 100 % of the 10 days.
        assertEquals(listOf(d(10, 23), d(10, 24), d(10, 28), d(10, 29), d(10, 30)), steps.map { it.dueDate })
        // 30 % of 6 h = 108 min → 105; 10 % = 36 → 30; 40 % = 144 → 150; 15 % = 54 → 60; 5 % = 18 → 15.
        assertEquals(listOf(105, 30, 150, 60, 15), steps.map { it.estimateMinutes })
        assertTrue(steps.all { it.parentId == 1L && it.moduleId == 7L && it.kind == TaskKind.TASK })
        assertEquals(listOf(0, 1, 2, 3, 4), steps.map { it.sortOrder })
    }

    @Test
    fun `templates cope with odd shares, no estimate, no date and a past date`() {
        val odd = BreakdownTemplate("Odd", listOf(TemplateStep("A", 1), TemplateStep(" ", 50), TemplateStep("B", 3), TemplateStep("C", 0)))
        val steps = BreakdownTemplates.apply(odd, essay.copy(estimateMinutes = null), today)
        assertEquals(listOf("A", "B"), steps.map { it.title }, "blank and zero steps are left out")
        assertEquals(listOf(today, d(10, 23)), steps.map { it.dueDate })
        assertTrue(steps.all { it.estimateMinutes == null })
        assertTrue(BreakdownTemplates.apply(odd, essay.copy(dueDate = null), today).all { it.dueDate == null })
        assertTrue(BreakdownTemplates.apply(odd, essay.copy(dueDate = d(10, 1)), today).all { it.dueDate == d(10, 1) })
        assertTrue(BreakdownTemplates.apply(BreakdownTemplate("Empty", emptyList()), essay, today).isEmpty())
        BreakdownTemplates.defaults.forEach { assertEquals(100, it.steps.sumOf { s -> s.percent }, it.name) }
    }

    @Test
    fun `a checklist keeps its order on the task`() {
        val task = Task(title = "Pack", checklist = listOf(ChecklistItem("Charger"), ChecklistItem("Notes", done = true)))
        assertEquals(listOf("Charger", "Notes"), task.checklist.map { it.text })
    }
}
