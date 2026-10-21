package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.DayWindows
import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitStatus
import io.github.matteopi.budgeting.core.planner.habits.HabitWeekState
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.GoalPeriod
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.core.planner.model.Habit
import io.github.matteopi.budgeting.core.planner.model.HabitCounting
import io.github.matteopi.budgeting.core.planner.model.HabitEntry
import io.github.matteopi.budgeting.core.planner.model.HabitGoal
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Habits' progress and streaks (docs/PLAN.md §13.4), as of Tue 20 Oct 2026 in London. */
class HabitProgressTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20)
    private fun oct(day: Int): LocalDate = LocalDate.of(2026, 10, day)
    private val settings = DaySettings()
    private val window = { date: LocalDate -> DayWindows.recordWindow(date, settings, zone) }
    private fun at(date: LocalDate, h: Int, m: Int = 0): Instant = ZonedDateTime.of(date.atTime(h, m), zone).toInstant()

    private val exercise = 3L
    private val phone = 9L

    private fun chunk(date: LocalDate, h: Int, minutes: Long, category: Long) =
        RecordChunk(start = at(date, h), end = at(date, h).plusSeconds(minutes * 60), categoryId = category)

    private fun tick(habit: Habit, vararg days: Int, amount: Int = 1) = days.map { HabitEntry(habitId = habit.id, date = oct(it), amount = amount) }

    private fun skip(habit: Habit, vararg days: Int) = days.map { HabitEntry(habitId = habit.id, date = oct(it), skipped = true) }

    private fun reviewed(vararg days: Int) = days.map { DayReview(oct(it), finishedAt = at(oct(it), 23)) }

    private fun evaluate(
        habit: Habit,
        entries: List<HabitEntry> = emptyList(),
        records: List<RecordChunk> = emptyList(),
        reviews: List<DayReview> = emptyList(),
        terms: List<TermPeriod> = emptyList(),
        from: LocalDate = habit.startDate,
        to: LocalDate = today.plusDays(1),
        on: LocalDate = today,
    ): HabitStatus = HabitProgress.evaluate(habit, entries, records, reviews, terms, window, from, to, on)

    private val gym = Habit(id = 1, name = "Gym", goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 3), startDate = oct(5))
    private val read = Habit(id = 2, name = "Read", goal = HabitGoal(GoalKind.AT_LEAST, 20, GoalUnit.MINUTES, GoalPeriod.DAY), startDate = oct(12))
    private val phoneLimit = Habit(
        id = 3, name = "Phone", goal = HabitGoal(GoalKind.AT_MOST, 60, GoalUnit.MINUTES, GoalPeriod.DAY),
        counting = HabitCounting.RECORD, categoryId = phone, startDate = oct(16),
    )

    // ---- The checkpoints ----

    @Test
    fun `gym three times a week is 1 of 3 this week with a two-week streak`() {
        val status = evaluate(gym, tick(gym, 5, 7, 9, 12, 14, 16, 19))
        assertEquals(1, status.current.amount)
        assertEquals(3, status.current.target)
        assertEquals(HabitWeekState.PENDING, status.week(oct(19))!!.state)
        assertEquals(listOf(HabitWeekState.DONE, HabitWeekState.DONE, HabitWeekState.PENDING), status.weeks.map { it.state })
        assertEquals(2, status.currentStreak)
        assertEquals(2, status.bestStreak)
        assertEquals(HabitDayState.DONE, status.day(oct(19))!!.state)
        assertEquals(HabitDayState.PENDING, status.day(today)!!.state, "still 2 to go this week")
        assertEquals(HabitDayState.NONE, status.day(oct(13))!!.state, "a day without a tick; the week judges")
    }

    @Test
    fun `reading 20 minutes a day with a skipped day has a seven-day streak`() {
        val entries = tick(read, 12, 13, amount = 20) + skip(read, 14) + tick(read, 15, 16, 17, 18, 19, amount = 25)
        val status = evaluate(read, entries)
        assertEquals(7, status.currentStreak)
        assertEquals(7, status.bestStreak)
        assertEquals(HabitDayState.SKIPPED, status.day(oct(14))!!.state)
        assertEquals(HabitDayState.PENDING, status.day(today)!!.state)
        assertEquals(0, status.current.amount)
        assertEquals(20, status.current.target)
    }

    @Test
    fun `a phone limit from the record breaks on the day it went over`() {
        val records = listOf(chunk(oct(16), 20, 40, phone), chunk(oct(17), 20, 55, phone), chunk(oct(18), 20, 75, phone), chunk(oct(19), 20, 90, phone))
        val status = evaluate(phoneLimit, records = records, reviews = reviewed(16, 17, 18))
        assertEquals(listOf(40, 55, 75, 90), (16..19).map { status.day(oct(it))!!.amount })
        assertEquals(
            listOf(HabitDayState.DONE, HabitDayState.DONE, HabitDayState.MISSED, HabitDayState.NOT_REVIEWED, HabitDayState.PENDING),
            (16..20).map { status.day(oct(it))!!.state },
        )
        assertEquals(0, status.currentStreak)
        assertEquals(2, status.bestStreak)
    }

    @Test
    fun `a ticked gym visit that is also recorded counts once`() {
        val both = gym.copy(counting = HabitCounting.BOTH, categoryId = exercise, minMinutes = 30)
        val status = evaluate(both, tick(both, 19), records = listOf(chunk(oct(19), 18, 60, exercise)))
        val day = status.day(oct(19))!!
        assertEquals(1, day.byHand)
        assertEquals(1, day.recorded)
        assertEquals(1, day.amount)
        assertEquals(1, status.current.amount)
    }

    @Test
    fun `a week with two skipped days needs round(3 x 5 over 7) = 2`() {
        val status = evaluate(gym, tick(gym, 12, 16) + skip(gym, 13, 14))
        val week = status.week(oct(12))!!
        assertEquals(2, week.target)
        assertEquals(2, week.amount)
        assertEquals(HabitWeekState.DONE, week.state)
        assertEquals(2, HabitProgress.weeklyTarget(3, 5))
        assertEquals(0, HabitProgress.weeklyTarget(1, 3), "a week whose target rounds to 0...")
        assertEquals(HabitWeekState.NEUTRAL, evaluate(gym.copy(goal = HabitGoal(GoalKind.TIMES_PER_WEEK, 1)), skip(gym, 5, 6, 7, 8)).week(oct(5))!!.state, "...is neutral")
    }

    // ---- Each goal kind ----

    @Test
    fun `on chosen days the other days rest and a missed day breaks the streak`() {
        val mwf = Habit(id = 4, name = "Run", goal = HabitGoal(GoalKind.ON_DAYS, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), startDate = oct(5))
        // Mon 5, Wed 7, Fri 9, Mon 12 done; Wed 14 missed; Fri 16 and Mon 19 done.
        val status = evaluate(mwf, tick(mwf, 5, 7, 9, 12, 16, 19))
        assertEquals(HabitDayState.REST, status.day(oct(6))!!.state)
        assertEquals(HabitDayState.MISSED, status.day(oct(14))!!.state)
        assertEquals(HabitDayState.REST, status.day(today)!!.state, "Tuesday is not a chosen day")
        assertEquals(2, status.currentStreak)
        assertEquals(4, status.bestStreak)
        assertTrue(status.weeks.isEmpty(), "a daily goal has no weeks")
    }

    @Test
    fun `at least an amount a week sums its days`() {
        val study = Habit(id = 5, name = "Language", goal = HabitGoal(GoalKind.AT_LEAST, 150, GoalUnit.MINUTES, GoalPeriod.WEEK), startDate = oct(12))
        val status = evaluate(study, tick(study, 12, amount = 60) + tick(study, 15, amount = 60) + tick(study, 18, amount = 40) + tick(study, 19, amount = 45))
        assertEquals(160, status.week(oct(12))!!.amount)
        assertEquals(HabitWeekState.DONE, status.week(oct(12))!!.state)
        assertEquals(45, status.current.amount)
        assertEquals(150, status.current.target)
        assertEquals(1, status.currentStreak)
    }

    @Test
    fun `a weekly limit is judged once the week is over and not while days are unreviewed`() {
        val limit = Habit(id = 6, name = "Gaming", goal = HabitGoal(GoalKind.AT_MOST, 300, GoalUnit.MINUTES, GoalPeriod.WEEK),
            counting = HabitCounting.RECORD, categoryId = phone, startDate = oct(5))
        // The week of 5 Oct: 200 minutes, every day reviewed. The week of 12 Oct: 100 minutes, the 18th not reviewed.
        val records = listOf(chunk(oct(6), 20, 200, phone), chunk(oct(13), 20, 100, phone), chunk(oct(19), 9, 200, phone), chunk(oct(19), 14, 200, phone))
        val status = evaluate(limit, records = records, reviews = reviewed(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 19))
        assertEquals(HabitWeekState.DONE, status.week(oct(5))!!.state)
        assertEquals(HabitWeekState.NEUTRAL, status.week(oct(12))!!.state)
        assertEquals(HabitWeekState.PENDING, status.week(oct(19))!!.state, "over already, but judged when the week ends")
        assertTrue(status.current.over)
        assertEquals(HabitDayState.NOT_REVIEWED, status.day(oct(18))!!.state)
        assertEquals(1, status.currentStreak)

        // Over the limit in a finished week breaks it, reviewed or not.
        val after = evaluate(limit, records = records, reviews = reviewed(5, 6, 7, 8, 9, 10, 11), on = oct(26))
        assertEquals(HabitWeekState.MISSED, after.week(oct(19))!!.state)
        assertEquals(0, after.currentStreak)
    }

    @Test
    fun `a daily limit by hand counts a day with nothing entered as within it`() {
        val sweets = Habit(id = 7, name = "Sweets", goal = HabitGoal(GoalKind.AT_MOST, 2, GoalUnit.COUNT, GoalPeriod.DAY), startDate = oct(17))
        val status = evaluate(sweets, tick(sweets, 18, amount = 3) + tick(sweets, 20, amount = 4))
        assertEquals(listOf(HabitDayState.DONE, HabitDayState.MISSED, HabitDayState.DONE, HabitDayState.PENDING), (17..20).map { status.day(oct(it))!!.state })
        assertTrue(status.current.over)
        assertEquals(1, status.currentStreak)
    }

    // ---- Pauses, term time, the start and the record ----

    @Test
    fun `paused days and days outside term don't count against a habit`() {
        val paused = read.copy(pausedFrom = oct(14), pausedUntil = oct(16))
        val status = evaluate(paused, tick(read, 12, 13, 17, 18, 19, amount = 20))
        assertEquals(listOf(HabitDayState.PAUSED, HabitDayState.PAUSED, HabitDayState.PAUSED), (14..16).map { status.day(oct(it))!!.state })
        assertEquals(5, status.currentStreak)

        val open = read.copy(pausedFrom = oct(19))
        assertEquals(HabitDayState.PAUSED, evaluate(open).day(today.plusDays(1))!!.state, "no end: paused until resumed")

        val autumn = TermPeriod(id = 1, name = "Autumn", startDate = oct(1), endDate = oct(16))
        val termOnly = read.copy(termOnly = true)
        val inTerm = evaluate(termOnly, tick(read, 12, 13, 14, 15, 16, amount = 20), terms = listOf(autumn))
        assertEquals(HabitDayState.PAUSED, inTerm.day(oct(17))!!.state)
        assertEquals(HabitDayState.PAUSED, inTerm.day(today)!!.state)
        assertEquals(5, inTerm.currentStreak, "the holiday doesn't break it")
        assertEquals(HabitDayState.PAUSED, evaluate(gym.copy(termOnly = true), terms = listOf(autumn)).day(oct(19))!!.state)
        assertEquals(HabitWeekState.NEUTRAL, evaluate(gym.copy(termOnly = true), terms = listOf(autumn)).week(oct(19))!!.state)
    }

    @Test
    fun `days before the start are left out`() {
        val status = evaluate(read, tick(read, 10, 11, amount = 20), from = oct(1))
        assertEquals(oct(12), status.days.first().date)
        assertEquals(0, status.bestStreak)
        assertNull(evaluate(read.copy(startDate = oct(25))).day(today))
        // A week that starts before the habit scales its target: gym from Thu 8 Oct has 4 days that week.
        val late = gym.copy(startDate = oct(8))
        assertEquals(2, evaluate(late, tick(late, 8, 9)).week(oct(5))!!.target)
    }

    @Test
    fun `record sessions are counted within the day's window, joined when touching and at least the minimum`() {
        val counted = gym.copy(counting = HabitCounting.RECORD, categoryId = exercise, minMinutes = 30)
        val records = listOf(
            // Two halves of one session: 20 + 20 minutes, touching, count once.
            chunk(oct(12), 18, 20, exercise), RecordChunk(start = at(oct(12), 18, 20), end = at(oct(12), 18, 40), categoryId = exercise),
            // Too short.
            chunk(oct(13), 9, 15, exercise),
            // Another category.
            chunk(oct(14), 9, 60, phone),
            // Two separate sessions in a day.
            chunk(oct(15), 9, 45, exercise), chunk(oct(15), 18, 45, exercise),
            // At 07:00, before the day's 08:00 start: in no day's window.
            chunk(oct(16), 7, 50, exercise),
        )
        val status = evaluate(counted, records = records)
        assertEquals(listOf(1, 0, 0, 2, 0), (12..16).map { status.day(oct(it))!!.recorded })
        assertEquals(HabitWeekState.DONE, status.week(oct(12))!!.state, "1 + 2 sessions make the 3")
    }

    @Test
    fun `minutes from the record are the sessions' minutes in the window`() {
        val reading = read.copy(counting = HabitCounting.RECORD, categoryId = exercise)
        // 23:30 to 00:30: half of it in the 19th's window (which ends at midnight).
        val status = evaluate(reading, records = listOf(RecordChunk(start = at(oct(19), 23, 30), end = at(oct(20), 0, 30), categoryId = exercise)))
        assertEquals(30, status.day(oct(19))!!.amount)
        assertEquals(HabitDayState.DONE, status.day(oct(19))!!.state)
        assertEquals(HabitDayState.MISSED, status.day(oct(18))!!.state)
        assertEquals(0, status.day(today)!!.amount, "00:00 to 00:30 is before the 20th's 08:00 start")
    }

    @Test
    fun `rates count judged days or weeks only`() {
        val status = evaluate(read, tick(read, 12, 13, amount = 20) + skip(read, 14), from = oct(12))
        // 12, 13 done; 14 skipped; 15 to 19 missed.
        assertEquals(2.0 / 7, HabitProgress.rate(status, oct(12), oct(20))!!, 1e-9)
        assertNull(HabitProgress.rate(status, oct(14), oct(14)))
        val weekly = evaluate(gym, tick(gym, 5, 7, 9, 12), from = oct(5))
        assertEquals(0.5, HabitProgress.rate(weekly, oct(1), oct(31))!!, 1e-9, "the week of 5 Oct done, 12 Oct missed, this one pending")
    }

    @Test
    fun `entries of other habits are ignored and a skipped day's amount doesn't count`() {
        val other = HabitEntry(habitId = 99, date = oct(19), amount = 5)
        val skippedWithAmount = HabitEntry(habitId = gym.id, date = oct(19), amount = 1, skipped = true)
        val status = evaluate(gym, listOf(other, skippedWithAmount))
        assertEquals(0, status.current.amount)
        assertEquals(HabitDayState.SKIPPED, status.day(oct(19))!!.state)
        assertFalse(status.current.over)
    }

    // ---- Random histories ----

    @Test
    fun `streaks are never negative and never longer than the history`() {
        val random = Random(20261020)
        repeat(2_000) {
            val kind = GoalKind.entries[random.nextInt(GoalKind.entries.size)]
            val period = if (random.nextBoolean()) GoalPeriod.DAY else GoalPeriod.WEEK
            val days = DayOfWeek.entries.filter { random.nextInt(3) == 0 }.toSet().ifEmpty { setOf(DayOfWeek.MONDAY) }
            val counting = HabitCounting.entries[random.nextInt(3)]
            val start = today.minusDays(random.nextLong(0, 120))
            val habit = Habit(
                id = 1, name = "H", goal = HabitGoal(kind, 1 + random.nextInt(5), if (random.nextBoolean()) GoalUnit.TIMES else GoalUnit.MINUTES, period, days),
                counting = counting, categoryId = exercise, minMinutes = if (random.nextBoolean()) null else 10 + random.nextInt(30),
                termOnly = random.nextInt(4) == 0, pausedFrom = if (random.nextInt(5) == 0) start.plusDays(random.nextLong(0, 30)) else null,
                startDate = start,
            )
            val entries = (0 until random.nextInt(60)).map {
                HabitEntry(habitId = 1, date = start.plusDays(random.nextLong(0, 125)), amount = random.nextInt(0, 6), skipped = random.nextInt(8) == 0)
            }.distinctBy { it.date }
            val records = (0 until random.nextInt(60)).map {
                val d = start.plusDays(random.nextLong(0, 125))
                chunk(d, 8 + random.nextInt(14), random.nextLong(5, 120), if (random.nextBoolean()) exercise else phone)
            }
            val reviews = (0 until random.nextInt(100)).map { start.plusDays(random.nextLong(0, 125)) }.distinct().map { DayReview(it, finishedAt = at(it, 23)) }
            val terms = listOf(TermPeriod(1, "T", start.plusDays(10), start.plusDays(60)))
            val status = evaluate(habit, entries, records, reviews, terms, from = start, to = today)
            val units = if (habit.goal.weekly) status.weeks.size else status.days.count { !it.date.isAfter(today) }
            assertTrue(status.currentStreak >= 0 && status.currentStreak <= status.bestStreak, "$habit: ${status.currentStreak} > ${status.bestStreak}")
            assertTrue(status.bestStreak <= units, "$habit: best ${status.bestStreak} over $units")
            assertTrue(status.days.all { !it.date.isBefore(start) && it.amount >= 0 })
        }
    }
}
