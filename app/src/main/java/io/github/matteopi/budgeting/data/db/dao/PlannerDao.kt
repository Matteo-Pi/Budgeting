package io.github.matteopi.budgeting.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.planner.ChecklistItemEntity
import io.github.matteopi.budgeting.data.db.entities.planner.ModuleEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TaskCompletionEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TaskEntity
import kotlinx.coroutines.flow.Flow

/** A task with its checklist, read in one transaction. Room promises no order; the mapper sorts by `position`. */
data class TaskWithChecklist(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "task_id")
    val checklist: List<ChecklistItemEntity>,
)

/** One row of the Done log: a completion with its task's title, kind and module. */
data class DoneLogRow(
    @Embedded val completion: TaskCompletionEntity,
    val title: String,
    val kind: String,
    @ColumnInfo(name = "module_id") val moduleId: Long?,
)

/**
 * The Planner's modules and to-do list (docs/PLAN.md §13.5). An abstract class so that [saveTask] can replace a
 * task and its checklist in one transaction. Reads are bounded: open tasks, one task, a deadline's steps, and the
 * Done log since a date. Only backups read everything.
 */
@Dao
abstract class PlannerDao {
    // ---- Modules ------------------------------------------------------------------------------

    @Query("SELECT * FROM planner_modules ORDER BY archived, sort_order, code_key, id")
    abstract fun observeModules(): Flow<List<ModuleEntity>>

    @Query("SELECT * FROM planner_modules ORDER BY archived, sort_order, code_key, id")
    abstract suspend fun getModules(): List<ModuleEntity>

    @Insert
    abstract suspend fun insertModule(module: ModuleEntity): Long

    @Insert
    abstract suspend fun insertModules(modules: List<ModuleEntity>)

    /** Returns the number of rows changed: 0 when there is no such module. */
    @Update
    abstract suspend fun updateModule(module: ModuleEntity): Int

    @Query("DELETE FROM planner_modules WHERE id = :id")
    abstract suspend fun deleteModule(id: Long)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM planner_modules")
    abstract suspend fun nextModuleSortOrder(): Int

    // ---- Tasks --------------------------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM planner_tasks WHERE completed_at_millis IS NULL ORDER BY due_date, due_minute, sort_order, id")
    abstract fun observeOpenTasks(): Flow<List<TaskWithChecklist>>

    @Transaction
    @Query("SELECT * FROM planner_tasks WHERE completed_at_millis IS NULL ORDER BY due_date, due_minute, sort_order, id")
    abstract suspend fun getOpenTasks(): List<TaskWithChecklist>

    @Transaction
    @Query("SELECT * FROM planner_tasks WHERE id = :id")
    abstract fun observeTask(id: Long): Flow<TaskWithChecklist?>

    @Transaction
    @Query("SELECT * FROM planner_tasks WHERE id = :id")
    abstract suspend fun getTask(id: Long): TaskWithChecklist?

    /** A deadline's steps, done or not, in their order. */
    @Transaction
    @Query("SELECT * FROM planner_tasks WHERE parent_id = :parentId ORDER BY sort_order, due_date, id")
    abstract fun observeSteps(parentId: Long): Flow<List<TaskWithChecklist>>

    @Transaction
    @Query("SELECT * FROM planner_tasks WHERE parent_id = :parentId ORDER BY sort_order, due_date, id")
    abstract suspend fun getSteps(parentId: Long): List<TaskWithChecklist>

    @Transaction
    @Query("SELECT * FROM planner_tasks ORDER BY id")
    abstract suspend fun getAllTasks(): List<TaskWithChecklist>

    @Insert
    abstract suspend fun insertTask(task: TaskEntity): Long

    @Insert
    abstract suspend fun insertTasks(tasks: List<TaskEntity>)

    /** Returns the number of rows changed: 0 when there is no such task. */
    @Update
    abstract suspend fun updateTask(task: TaskEntity): Int

    @Query("DELETE FROM planner_tasks WHERE id = :id")
    abstract suspend fun deleteTask(id: Long)

    @Query("UPDATE planner_tasks SET completed_at_millis = :completedAtMillis WHERE id = :id")
    abstract suspend fun setCompletedAt(id: Long, completedAtMillis: Long?)

    @Query("UPDATE planner_tasks SET due_date = :dueDate WHERE id = :id")
    abstract suspend fun setDueDate(id: Long, dueDate: Long?)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM planner_tasks")
    abstract suspend fun nextTaskSortOrder(): Int

    // ---- Checklists ---------------------------------------------------------------------------

    @Insert
    abstract suspend fun insertChecklistItems(items: List<ChecklistItemEntity>)

    @Query("DELETE FROM planner_checklist_items WHERE task_id = :taskId")
    abstract suspend fun deleteChecklist(taskId: Long)

    @Query("UPDATE planner_checklist_items SET done = :done WHERE task_id = :taskId AND position = :position")
    abstract suspend fun setChecklistItemDone(taskId: Long, position: Int, done: Boolean)

    @Query("SELECT * FROM planner_checklist_items ORDER BY task_id, position")
    abstract suspend fun getAllChecklistItems(): List<ChecklistItemEntity>

    /**
     * Inserts [task] (id 0) or updates it, then replaces its checklist with [checklist] in that order. An unknown id
     * is an error. Returns the task's id.
     */
    @Transaction
    open suspend fun saveTask(task: TaskEntity, checklist: List<ChecklistItemEntity>): Long {
        val id = if (task.id == 0L) {
            insertTask(task)
        } else {
            val changed = updateTask(task)
            require(changed > 0) { "There is no task ${task.id} to update" }
            deleteChecklist(task.id)
            task.id
        }
        if (checklist.isNotEmpty()) {
            insertChecklistItems(checklist.mapIndexed { index, item -> item.copy(id = 0, taskId = id, position = index) })
        }
        return id
    }

    // ---- The Done log -------------------------------------------------------------------------

    @Insert
    abstract suspend fun insertCompletion(completion: TaskCompletionEntity): Long

    @Insert
    abstract suspend fun insertCompletions(completions: List<TaskCompletionEntity>)

    @Query("DELETE FROM planner_task_completions WHERE id = :id")
    abstract suspend fun deleteCompletion(id: Long)

    @Query(
        "DELETE FROM planner_task_completions WHERE id = (SELECT id FROM planner_task_completions WHERE task_id = :taskId " +
            "ORDER BY completed_at_millis DESC, id DESC LIMIT 1)",
    )
    abstract suspend fun deleteLatestCompletion(taskId: Long)

    @Query("SELECT * FROM planner_task_completions ORDER BY id")
    abstract suspend fun getAllCompletions(): List<TaskCompletionEntity>

    @Query("SELECT * FROM planner_task_completions WHERE task_id IN (:taskIds) ORDER BY id")
    abstract suspend fun getCompletionsFor(taskIds: List<Long>): List<TaskCompletionEntity>

    @Query(
        "SELECT c.*, t.title AS title, t.kind AS kind, t.module_id AS module_id FROM planner_task_completions c " +
            "JOIN planner_tasks t ON t.id = c.task_id WHERE c.completed_at_millis >= :fromMillis " +
            "ORDER BY c.completed_at_millis DESC, c.id DESC",
    )
    abstract fun observeDoneLog(fromMillis: Long): Flow<List<DoneLogRow>>

    // ---- Backups ------------------------------------------------------------------------------

    @Query("DELETE FROM planner_task_completions")
    abstract suspend fun deleteAllCompletions()

    @Query("DELETE FROM planner_checklist_items")
    abstract suspend fun deleteAllChecklistItems()

    /** Steps first: they point at their deadlines. */
    @Query("DELETE FROM planner_tasks WHERE parent_id IS NOT NULL")
    abstract suspend fun deleteAllSteps()

    @Query("DELETE FROM planner_tasks")
    abstract suspend fun deleteAllTasks()

    @Query("DELETE FROM planner_modules")
    abstract suspend fun deleteAllModules()
}
