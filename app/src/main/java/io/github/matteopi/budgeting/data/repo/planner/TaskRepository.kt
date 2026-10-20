package io.github.matteopi.budgeting.data.repo.planner

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskCompletion
import io.github.matteopi.budgeting.core.planner.todo.TaskRepeats
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.PlannerDao
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One entry of the Done log, with what the To-do tab shows of its task. */
data class DoneItem(
    val completion: TaskCompletion,
    val title: String,
    val isDeadline: Boolean,
    val moduleId: Long?,
)

/** What [TaskRepository.complete] changed, so *Undo* can put it back exactly. */
data class CompletionUndo(
    val completionId: Long,
    val task: Task,
)

/** A deleted task with its steps and Done log entries, so *Undo* can restore it with the same ids. */
data class DeletedTask(
    val task: Task,
    val steps: List<Task>,
    val completions: List<TaskCompletion>,
)

/**
 * The to-do list (docs/PLAN.md §13.5): tasks, deadlines and their steps, checklists and the Done log. Each user
 * action is one database transaction, and every write tells [PlannerUpdater] so the reminders follow.
 */
class TaskRepository(
    private val database: BudgetDatabase,
    private val plannerDao: PlannerDao,
    private val clock: Clock,
    private val plannerUpdater: PlannerUpdater,
) {
    fun observeOpenTasks(): Flow<List<Task>> = plannerDao.observeOpenTasks().map { list -> list.map { it.toModel() } }

    suspend fun getOpenTasks(): List<Task> = plannerDao.getOpenTasks().map { it.toModel() }

    fun observeTask(id: Long): Flow<Task?> = plannerDao.observeTask(id).map { it?.toModel() }

    suspend fun getTask(id: Long): Task? = plannerDao.getTask(id)?.toModel()

    fun observeSteps(parentId: Long): Flow<List<Task>> = plannerDao.observeSteps(parentId).map { list -> list.map { it.toModel() } }

    /** The Done log from [since] on, newest first. */
    fun observeDoneLog(since: LocalDate): Flow<List<DoneItem>> {
        val fromMillis = since.atStartOfDay(clock.zone()).toInstant().toEpochMilli()
        return plannerDao.observeDoneLog(fromMillis).map { rows ->
            rows.map { row ->
                DoneItem(row.completion.toModel(), row.title, row.kind == "DEADLINE", row.moduleId)
            }
        }
    }

    /** Inserts [task] (id 0: added at the end of the list, created now) or updates it with its checklist. Returns its id. */
    suspend fun save(task: Task): Long {
        val id = database.withTransaction {
            val toSave = if (task.id == 0L) {
                task.copy(createdAt = Instant.ofEpochMilli(clock.nowMillis()), sortOrder = plannerDao.nextTaskSortOrder())
            } else {
                task
            }
            plannerDao.saveTask(toSave.toEntity(), toSave.toChecklistEntities())
        }
        plannerUpdater.changed()
        return id
    }

    /** Adds [steps] (new tasks, each pointing at its deadline) in one transaction, in their order. Returns their ids. */
    suspend fun addSteps(steps: List<Task>): List<Long> {
        if (steps.isEmpty()) return emptyList()
        val ids = database.withTransaction {
            val now = Instant.ofEpochMilli(clock.nowMillis())
            var order = plannerDao.nextTaskSortOrder()
            steps.map { step ->
                plannerDao.saveTask(step.copy(id = 0, createdAt = now, sortOrder = order++).toEntity(), step.toChecklistEntities())
            }
        }
        plannerUpdater.changed()
        return ids
    }

    /**
     * Ticks [task] off. A repeating task logs the completion and moves on to its next date (its checklist starts
     * again); any other task is marked done. Returns what *Undo* needs.
     */
    suspend fun complete(task: Task): CompletionUndo {
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val today = clock.today()
        val undo = database.withTransaction {
            val current = plannerDao.getTask(task.id)?.toModel() ?: task
            val completionId = plannerDao.insertCompletion(
                TaskCompletion(taskId = current.id, dueDate = current.dueDate, completedAt = now).toEntity(),
            )
            val repeat = current.repeat
            val due = current.dueDate
            if (repeat != null && due != null) {
                val next = current.copy(
                    dueDate = TaskRepeats.next(due, repeat, today),
                    checklist = current.checklist.map { it.copy(done = false) },
                )
                plannerDao.saveTask(next.toEntity(), next.toChecklistEntities())
            } else {
                plannerDao.setCompletedAt(current.id, now.toEpochMilli())
            }
            CompletionUndo(completionId, current)
        }
        plannerUpdater.changed()
        return undo
    }

    /** *Undo* of [complete]: removes that Done log entry and puts the task back as it was. */
    suspend fun undoComplete(undo: CompletionUndo) {
        database.withTransaction {
            plannerDao.deleteCompletion(undo.completionId)
            if (plannerDao.getTask(undo.task.id) != null) {
                plannerDao.saveTask(undo.task.toEntity(), undo.task.toChecklistEntities())
            }
        }
        plannerUpdater.changed()
    }

    /**
     * *Not done after all*, from the Done log: removes entry [completionId] of [taskId] and, for a task that doesn't
     * repeat, opens it again. A repeating task keeps its current date.
     */
    suspend fun markNotDone(completionId: Long, taskId: Long) {
        database.withTransaction {
            plannerDao.deleteCompletion(completionId)
            val task = plannerDao.getTask(taskId)?.toModel()
            if (task != null && task.repeat == null) plannerDao.setCompletedAt(task.id, null)
        }
        plannerUpdater.changed()
    }

    /** Opens a done task (a step ticked again) and removes its latest Done log entry. */
    suspend fun reopen(task: Task) {
        database.withTransaction {
            plannerDao.deleteLatestCompletion(task.id)
            plannerDao.setCompletedAt(task.id, null)
        }
        plannerUpdater.changed()
    }

    /** Moves [task] to [date] (its time stays). */
    suspend fun moveTo(task: Task, date: LocalDate) {
        plannerDao.setDueDate(task.id, date.toEpochDay())
        plannerUpdater.changed()
    }

    /** Ticks or unticks line [position] of [taskId]'s checklist. */
    suspend fun setChecklistItem(taskId: Long, position: Int, done: Boolean) {
        plannerDao.setChecklistItemDone(taskId, position, done)
    }

    /** Deletes [taskId] with its steps, checklists and Done log entries. Returns what *Undo* needs, or null if it was gone. */
    suspend fun delete(taskId: Long): DeletedTask? {
        val deleted = database.withTransaction {
            val task = plannerDao.getTask(taskId)?.toModel() ?: return@withTransaction null
            val steps = plannerDao.getSteps(taskId).map { it.toModel() }
            val ids = (steps.map { it.id } + taskId).toSet()
            val completions = plannerDao.getCompletionsFor(ids.toList()).map { it.toModel() }
            plannerDao.deleteTask(taskId)
            DeletedTask(task, steps, completions)
        }
        plannerUpdater.changed()
        return deleted
    }

    /** *Undo* of [delete]: the task, then its steps, then its Done log, all with their old ids. */
    suspend fun restore(deleted: DeletedTask) {
        database.withTransaction {
            plannerDao.insertTasks(listOf(deleted.task.toEntity()) + deleted.steps.map { it.toEntity() })
            plannerDao.insertChecklistItems(
                (listOf(deleted.task) + deleted.steps).flatMap { task -> task.toChecklistEntities().map { it.copy(taskId = task.id) } },
            )
            plannerDao.insertCompletions(deleted.completions.map { it.toEntity() })
        }
        plannerUpdater.changed()
    }
}
