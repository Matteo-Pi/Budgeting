package io.github.matteopi.budgeting.data.repo.planner

import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.data.db.dao.HabitDao
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Habits and the days filled in by hand (docs/PLAN.md §13.5). A habit is archived, never deleted, so the plan blocks
 * that name it stay valid. Each change to a day returns the entry it replaced (or null), for *Undo*.
 */
class HabitRepository(
    private val habitDao: HabitDao,
    private val plannerUpdater: PlannerUpdater,
) {
    fun observeHabits(): Flow<List<Habit>> = habitDao.observeHabits().map { list -> list.map { it.toModel() } }

    suspend fun getHabits(): List<Habit> = habitDao.getHabits().map { it.toModel() }

    suspend fun getHabit(id: Long): Habit? = habitDao.getHabit(id)?.toModel()

    /** Adds [habit] (id 0, placed last) or saves it. Returns its id. */
    suspend fun save(habit: Habit): Long {
        val id = if (habit.id == 0L) {
            val last = habitDao.getHabits().maxOfOrNull { it.sortOrder } ?: -1
            habitDao.insertHabit(habit.copy(sortOrder = last + 1).toEntity())
        } else {
            habitDao.updateHabit(habit.toEntity())
            habit.id
        }
        plannerUpdater.changed()
        return id
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val habit = getHabit(id) ?: return
        save(habit.copy(archived = archived))
    }

    fun observeEntries(from: LocalDate, to: LocalDate): Flow<List<HabitEntry>> =
        habitDao.observeEntries(from.toEpochDay(), to.toEpochDay()).map { list -> list.map { it.toModel() } }

    suspend fun getEntries(from: LocalDate, to: LocalDate): List<HabitEntry> =
        habitDao.getEntries(from.toEpochDay(), to.toEpochDay()).map { it.toModel() }

    suspend fun getEntry(habitId: Long, date: LocalDate): HabitEntry? = habitDao.getEntry(habitId, date.toEpochDay())?.toModel()

    /** Sets [date]'s amount by hand (0 clears the day). Returns the entry it replaced. */
    suspend fun setAmount(habitId: Long, date: LocalDate, amount: Int): HabitEntry? =
        replace(habitId, date, if (amount > 0) HabitEntry(habitId = habitId, date = date, amount = amount) else null)

    /**
     * *Done* (a tick on Today, or a reminder's button): [date] gets the habit's tick amount by hand
     * ([HabitProgress.tickAmount]), unless it already has as much. Returns the entry it replaced; null and nothing done
     * for a habit that isn't ticked (a limit, a weekly amount).
     */
    suspend fun markDone(habit: Habit, date: LocalDate): HabitEntry? {
        val amount = HabitProgress.tickAmount(habit.goal) ?: return null
        val current = getEntry(habit.id, date)
        if (current != null && !current.skipped && current.amount >= amount) return current
        return setAmount(habit.id, date, amount)
    }

    /** *Skip today* (or un-skip): a skipped day keeps nothing by hand. Returns the entry it replaced. */
    suspend fun setSkipped(habitId: Long, date: LocalDate, skipped: Boolean): HabitEntry? =
        replace(habitId, date, if (skipped) HabitEntry(habitId = habitId, date = date, skipped = true) else null)

    /** Puts [previous] back for [habitId] on [date] (null clears it): *Undo*. */
    suspend fun restore(habitId: Long, date: LocalDate, previous: HabitEntry?) {
        replace(habitId, date, previous)
    }

    private suspend fun replace(habitId: Long, date: LocalDate, entry: HabitEntry?): HabitEntry? {
        val before = getEntry(habitId, date)
        if (entry == null) {
            habitDao.deleteEntry(habitId, date.toEpochDay())
        } else {
            // The unique (habit, date) index makes this replace the day's row.
            habitDao.upsertEntry(entry.copy(id = before?.id ?: 0, habitId = habitId, date = date).toEntity())
        }
        plannerUpdater.changed()
        return before
    }
}
