package io.github.matteopi.budgeting.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.todo.TaskQuickAdd
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.openTasks
import io.github.matteopi.budgeting.testing.plannerToday
import io.github.matteopi.budgeting.testing.seedModule
import io.github.matteopi.budgeting.testing.seedPlannerSection
import io.github.matteopi.budgeting.testing.seedTask
import io.github.matteopi.budgeting.testing.taskById
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.taskRow
import io.github.matteopi.budgeting.testing.tickTask
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.planner.components.PlannerTags
import io.github.matteopi.budgeting.ui.planner.task.TaskEditTags
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Planner's to-do list on screen (docs/PLAN.md §13.10, stage P1), with screenshots `todo-*`: the quick-add line,
 * ticking off with *Undo*, a repeating task moving on, the Done log, a deadline split with a template, and the editor.
 */
@RunWith(AndroidJUnit4::class)
class TodoTest : UiTest() {

    @After
    fun noAlarmLeftBehind() {
        appContainer.plannerAlarms.cancel()
    }

    private fun openPlanner() {
        launchMain()
        waitForExists(clickableText("Today"), "the Today tab", HEAVY_TIMEOUT_MS)
    }

    @Test
    fun theQuickAddLineReadsTheCheckpointLine() {
        seedPlannerSection()
        val ec101 = seedModule("EC101", "Economics")
        openPlanner()
        goToTab("To-do")

        val line = "Econ essay draft fri 3pm 2h #EC101"
        typeInTag(PlannerTags.QUICK_ADD, line)
        val expectedDate = TaskQuickAdd.parse(line, plannerToday()).dueDate!!
        for (chip in listOf(PlannerText.date(expectedDate, plannerToday()), "15:00", "2 h", "EC101")) {
            waitForExists(txt(chip) and hasAnyAncestor(hasTestTag(PlannerTags.QUICK_ADD_PREVIEW)), "the '$chip' chip")
        }
        shot("todo-01-quick-add-preview")
        tapIcon("Add")
        waitForText("Added: Econ essay draft")

        val task = openTasks().single()
        assertEquals("Econ essay draft", task.title)
        assertEquals(expectedDate, task.dueDate)
        assertEquals(LocalTime.of(15, 0), task.dueTime)
        assertEquals(120, task.estimateMinutes)
        assertEquals(ec101, task.moduleId)

        tapText("By module")
        waitForText("EC101 Economics")
        waitForExists(taskRow("Econ essay draft"), "the new task under its module")
        shot("todo-02-by-module")
    }

    @Test
    fun tickingOffWithUndoARepeatingTaskAndTheDoneLog() {
        seedPlannerSection()
        val today = plannerToday()
        val book = seedTask(Task(title = "Return library book", dueDate = today))
        val laundry = seedTask(Task(title = "Laundry", dueDate = today, repeat = TaskRepeat(RepeatKind.WEEKS)))
        openPlanner()

        // Today lists both; ticking one off offers Undo, which puts it back.
        waitForExists(taskRow("Return library book"), "the book on Today")
        waitForExists(taskRow("Laundry"), "the laundry on Today")
        shot("todo-03-today")
        tickTask("Return library book")
        waitForText("Done: Return library book")
        tapText("Undo")
        waitFor("the book to be open again") { taskById(book)?.completedAt == null }
        waitForExists(taskRow("Return library book"), "the book back on Today")

        // Ticked for good, it leaves the list; the weekly laundry moves on a week.
        tickTask("Return library book")
        waitUntilGone(taskRow("Return library book"), "the book after ticking it off")
        tickTask("Laundry")
        waitFor("the laundry to move to next week") { taskById(laundry)?.dueDate == today.plusDays(7) }
        assertNull(taskById(laundry)?.completedAt)

        // Both are in the Done log; "Not done" opens the book again.
        goToTab("To-do")
        tapText("Done")
        waitForText("Return library book")
        waitForText("Laundry")
        shot("todo-04-done-log")
        tap(clickableText("Not done") and hasAnyAncestor(tagged(PlannerTags.DONE_ROW, "Return library book")), "Not done on the book")
        waitFor("the book to be open again") { taskById(book)?.completedAt == null }
    }

    @Test
    fun aDeadlineIsSplitWithTheEssayTemplate() {
        seedPlannerSection()
        val today = plannerToday()
        seedTask(Task(title = "EC101 essay", kind = TaskKind.DEADLINE, dueDate = today.plusDays(10), estimateMinutes = 360))
        openPlanner()
        goToTab("To-do")
        tapText("Upcoming")
        waitForExists(taskRow("EC101 essay"), "the deadline under Upcoming")
        assertTrue(textExists("6 h left · due in 10 days"))
        tap(taskRow("EC101 essay"), "the deadline")

        waitForText("Due in 10 days")
        waitForText("6 h of work left of 6 h")
        tapText("From a template")
        tapMenuItem("Essay")
        for (step in listOf("Research", "Outline", "First draft", "Edit", "References and submit")) {
            waitForExists(taskRow(step), "the step '$step'")
        }
        waitForText("0 of 5 steps done")
        tickTask("Research")
        waitForText("1 of 5 steps done")
        shot("todo-05-deadline-steps")
    }

    @Test
    fun theEditorSavesATaskWithATimeAReminderAndAChecklist() {
        seedPlannerSection()
        openPlanner()
        goToTab("To-do")
        tapText("Add task")
        typeInTag(TaskEditTags.TITLE, "Read chapter 3")
        assertTrue(textExists("Save"))
        tapText("Due today")
        typeInTag(TaskEditTags.TIME, "1530")
        typeInTag(TaskEditTags.ESTIMATE, "45m")
        tapText("15 minutes before")
        tapText("Add item")
        type("Item 1", "Take notes")
        shot("todo-06-editor")
        tap(hasTestTag(TaskEditTags.SAVE), "Save")

        waitForExists(taskRow("Read chapter 3"), "the new task on To-do")
        waitForText("Due today at 15:30 · 45 m · 0/1 done")
        val task = openTasks().single()
        assertEquals(LocalTime.of(15, 30), task.dueTime)
        assertEquals(45, task.estimateMinutes)
        assertEquals(15, task.reminderMinutesBefore)
        assertEquals(listOf("Take notes"), task.checklist.map { it.text })
    }
}
