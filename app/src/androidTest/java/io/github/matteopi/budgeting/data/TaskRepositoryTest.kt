package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import io.github.matteopi.budgeting.core.planner.todo.TaskRepeats
import io.github.matteopi.budgeting.testing.DataTestSupport
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The Planner's to-do list against the real database (docs/PLAN.md §13.5). */
@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {
    private val container get() = DataTestSupport.container
    private val tasks get() = container.taskRepository
    private val today get() = DataTestSupport.today()

    @After
    fun noAlarmLeftBehind() {
        container.plannerAlarms.cancel()
    }

    @Test
    fun aTaskIsSavedWithItsChecklistAndEditedInPlace() = runBlocking<Unit> {
        val id = tasks.save(Task(title = "Pack", dueDate = today, checklist = listOf(ChecklistItem("Charger"), ChecklistItem("Notes"))))
        val saved = tasks.getTask(id)!!
        assertTrue(saved.createdAt.toEpochMilli() > 0, "new tasks get their creation time")
        assertEquals(listOf("Charger", "Notes"), saved.checklist.map { it.text })

        tasks.setChecklistItem(id, 1, true)
        assertEquals(listOf(false, true), tasks.getTask(id)!!.checklist.map { it.done })

        tasks.save(saved.copy(title = "Pack bag", checklist = listOf(ChecklistItem("Notes", done = true))))
        val edited = tasks.getTask(id)!!
        assertEquals("Pack bag", edited.title)
        assertEquals(listOf(ChecklistItem("Notes", done = true)), edited.checklist)
        assertEquals(saved.createdAt, edited.createdAt)
        assertEquals(listOf(id), tasks.observeOpenTasks().first().map { it.id })
    }

    @Test
    fun completingAOneOffTaskLogsItAndUndoPutsItBack() = runBlocking<Unit> {
        val id = tasks.save(Task(title = "Return book", dueDate = today))
        val undo = tasks.complete(tasks.getTask(id)!!)
        assertNotNull(tasks.getTask(id)!!.completedAt)
        assertTrue(tasks.observeOpenTasks().first().isEmpty())
        val log = tasks.observeDoneLog(today.minusDays(1)).first()
        assertEquals(listOf("Return book"), log.map { it.title })

        tasks.undoComplete(undo)
        assertNull(tasks.getTask(id)!!.completedAt)
        assertTrue(tasks.observeDoneLog(today.minusDays(1)).first().isEmpty())
    }

    @Test
    fun aRepeatingTaskMovesOnAndItsChecklistStartsAgain() = runBlocking<Unit> {
        val repeat = TaskRepeat(RepeatKind.WEEKS)
        val id = tasks.save(Task(title = "Laundry", dueDate = today, repeat = repeat, checklist = listOf(ChecklistItem("Whites", done = true))))
        val undo = tasks.complete(tasks.getTask(id)!!)

        val moved = tasks.getTask(id)!!
        assertNull(moved.completedAt, "a repeating task stays open")
        assertEquals(TaskRepeats.next(today, repeat, today), moved.dueDate)
        assertEquals(today.plusDays(7), moved.dueDate)
        assertEquals(listOf(false), moved.checklist.map { it.done })
        assertEquals(1, tasks.observeDoneLog(today.minusDays(1)).first().size)

        tasks.undoComplete(undo)
        val back = tasks.getTask(id)!!
        assertEquals(today, back.dueDate)
        assertEquals(listOf(true), back.checklist.map { it.done })

        // From the Done log, "not done" keeps a repeating task's current date.
        tasks.complete(back)
        val entry = tasks.observeDoneLog(today.minusDays(1)).first().single()
        tasks.markNotDone(entry.completion.id, entry.completion.taskId)
        assertEquals(today.plusDays(7), tasks.getTask(id)!!.dueDate)
        assertTrue(tasks.observeDoneLog(today.minusDays(1)).first().isEmpty())
    }

    @Test
    fun aDeadlineSplitWithATemplateAndDeletedWithUndo() = runBlocking<Unit> {
        val moduleId = container.moduleRepository.save(Module(code = "EC101"))!!
        val essayId = tasks.save(Task(title = "Essay", kind = TaskKind.DEADLINE, dueDate = today.plusDays(10), estimateMinutes = 360, moduleId = moduleId))
        val essay = tasks.getTask(essayId)!!
        val stepIds = tasks.addSteps(BreakdownTemplates.apply(BreakdownTemplates.defaults.first(), essay, today))
        assertEquals(5, stepIds.size)
        val steps = tasks.observeSteps(essayId).first()
        assertEquals(listOf("Research", "Outline", "First draft", "Edit", "References and submit"), steps.map { it.title })
        assertTrue(steps.all { it.parentId == essayId && it.moduleId == moduleId })
        assertEquals(essay.dueDate, steps.last().dueDate)

        tasks.complete(steps.first())
        tasks.reopen(tasks.getTask(steps.first().id)!!)
        assertNull(tasks.getTask(steps.first().id)!!.completedAt)
        tasks.complete(steps.first())

        val deleted = tasks.delete(essayId)!!
        assertNull(tasks.getTask(essayId))
        assertTrue(tasks.observeSteps(essayId).first().isEmpty(), "the steps go with the deadline")
        assertEquals(1, deleted.completions.size)

        tasks.restore(deleted)
        assertEquals(stepIds, tasks.observeSteps(essayId).first().map { it.id })
        assertEquals(1, tasks.observeDoneLog(today.minusDays(1)).first().size)
    }

    @Test
    fun moduleCodesAreUniqueAndDeletingAModuleKeepsItsTasks() = runBlocking<Unit> {
        val modules = container.moduleRepository
        val id = modules.save(Module(code = "EC101", name = "Economics"))!!
        assertNull(modules.save(Module(code = "ec101")), "codes are unique without case")
        assertNotNull(modules.save(modules.getModules().single().copy(name = "Principles of Economics")), "editing keeps its own code")
        val taskId = tasks.save(Task(title = "Reading", moduleId = id))
        modules.delete(id)
        assertNull(tasks.getTask(taskId)!!.moduleId)
        assertTrue(modules.getModules().isEmpty())
    }

    @Test
    fun movingATaskKeepsItsTime() = runBlocking<Unit> {
        val id = tasks.save(Task(title = "Seminar prep", dueDate = today, dueTime = LocalTime.of(9, 0)))
        tasks.moveTo(tasks.getTask(id)!!, today.plusDays(1))
        val moved = tasks.getTask(id)!!
        assertEquals(today.plusDays(1), moved.dueDate)
        assertEquals(LocalTime.of(9, 0), moved.dueTime)
    }
}
