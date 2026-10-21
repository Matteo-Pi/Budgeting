package io.github.matteopi.budgeting.ui.planner.habits

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.data.repo.planner.HabitRepository
import java.time.LocalDate

/** What the screen says after a change to a habit's day, with its *Undo*. */
class HabitEvent(val message: String, val undo: suspend () -> Unit)

/**
 * The changes a habit's day takes from the Habits tab and Today (docs/PLAN.md §12.9): a tick, an amount added, or *Skip
 * today*. Each returns the event to show, which puts the day back as it was.
 */
class HabitActions(private val habitRepository: HabitRepository) {

    /** Ticks [date] done, or clears the tick when it is already done. */
    suspend fun toggle(habitId: Long, date: LocalDate): HabitEvent? {
        val habit = habitRepository.getHabit(habitId) ?: return null
        val tick = HabitProgress.tickAmount(habit.goal) ?: return null
        val current = habitRepository.getEntry(habitId, date)
        val done = current != null && !current.skipped && current.amount >= tick
        val previous = if (done) habitRepository.setAmount(habitId, date, 0) else habitRepository.markDone(habit, date)
        return HabitEvent(if (done) "Not done: ${habit.name}" else "Done: ${habit.name}") { habitRepository.restore(habitId, date, previous) }
    }

    /** Adds [amount] to what [date] has by hand ("+20 minutes"). */
    suspend fun add(habitId: Long, date: LocalDate, amount: Int): HabitEvent? {
        if (amount <= 0) return null
        val habit = habitRepository.getHabit(habitId) ?: return null
        val current = habitRepository.getEntry(habitId, date)?.takeIf { !it.skipped }?.amount ?: 0
        val previous = habitRepository.setAmount(habitId, date, current + amount)
        val text = if (habit.goal.unit == GoalUnit.MINUTES) PlannerText.duration(amount) else "$amount"
        return HabitEvent("Added $text to ${habit.name}") { habitRepository.restore(habitId, date, previous) }
    }

    /** *Skip today* (ill, away), or takes the skip back. */
    suspend fun skip(habitId: Long, date: LocalDate, skipped: Boolean): HabitEvent? {
        val habit = habitRepository.getHabit(habitId) ?: return null
        val previous = habitRepository.setSkipped(habitId, date, skipped)
        return HabitEvent(if (skipped) "Skipped today: ${habit.name}" else "Not skipped: ${habit.name}") {
            habitRepository.restore(habitId, date, previous)
        }
    }
}
