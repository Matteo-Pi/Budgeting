package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.PlanOrigin
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Backup version 8: habits and their entries (docs/PLAN.md §13.5). */
class HabitBackupTest {

    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private val oct = { day: Int -> LocalDate.of(2026, 10, day) }
    private val habits = listOf(
        Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), counting = HabitCounting.BOTH, categoryId = 3, minMinutes = 30,
            reminderTime = LocalTime.of(18, 15), startDate = oct(5), sortOrder = 0),
        Habit(id = 2, name = "Phone", colorArgb = 0xFFC2185B, goal = HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES, GoalPeriod.DAY),
            counting = HabitCounting.RECORD, categoryId = 9, streaks = false, termOnly = true, pausedFrom = oct(24), pausedUntil = oct(31),
            startDate = oct(16), archived = true, sortOrder = 1),
        Habit(id = 3, name = "Read", goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)), suggestSlot = true,
            slotMinutes = 30, slotStart = LocalTime.of(20, 0), slotEnd = LocalTime.of(23, 0), startDate = oct(12), sortOrder = 2),
    )
    private val entries = listOf(
        HabitEntry(id = 1, habitId = 1, date = oct(5), amount = 1),
        HabitEntry(id = 2, habitId = 1, date = oct(6), skipped = true),
        HabitEntry(id = 3, habitId = 3, date = oct(12), amount = 1),
    )
    private val block = PlanBlock(id = 1, start = Instant.ofEpochMilli(1_000), end = Instant.ofEpochMilli(2_000), categoryId = 3, habitId = 1,
        origin = PlanOrigin.HABIT)
    private val data = BackupData(plannerCategories = categories, plannerHabits = habits, plannerHabitEntries = entries, plannerPlanBlocks = listOf(block))

    private fun encode(d: BackupData) = BackupCodec.encode(BackupMapper.toBackup(d, exportedAt = "now"))

    private fun errors(d: BackupData) = assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(encode(d))).errors

    @Test
    fun `habits and their entries round trip`() {
        assertEquals(data, assertIs<BackupDecodeResult.Success>(BackupCodec.decode(encode(data))).data)
        assertTrue(encode(data).contains("\"goalKind\": \"TIMES_PER_WEEK\""))
        assertTrue(encode(data).contains("\"days\": 65"), "Monday and Sunday as bits 0 and 6")
    }

    @Test
    fun `a version 7 file has no habits`() {
        val json = """{"format":"budgeting-backup","version":7,"exportedAt":"now",
            "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}"""
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertTrue(back.plannerHabits.isEmpty() && back.plannerHabitEntries.isEmpty())
    }

    @Test
    fun `broken references, duplicates and bad values fail cleanly`() {
        assertTrue(errors(data.copy(plannerCategories = categories.take(5))).any { it.contains("Habit 2 refers to a missing category 9") })
        assertTrue(errors(data.copy(plannerHabits = habits.drop(1))).any { it.contains("A habit entry refers to a missing habit 1") })
        assertTrue(errors(data.copy(plannerHabits = habits.drop(1))).any { it.contains("Plan block 1 refers to a missing habit 1") })
        assertTrue(errors(data.copy(plannerHabits = habits + habits[0].copy(name = "Gym again"))).any { it.contains("Two habits have the same id") })
        assertTrue(errors(data.copy(plannerHabitEntries = entries + entries[0].copy(id = 9))).any { it.contains("two entries on the same day") })
        val badTime = encode(data).replace("\"reminderMinute\": 1095", "\"reminderMinute\": 1500")
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(badTime)).errors.any { it.contains("time outside the day") })
        val badKind = encode(data).replace("\"TIMES_PER_WEEK\"", "\"SOMETIMES\"")
        assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(badKind))
    }
}
