package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.Intervals
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.suggest.Demand
import io.github.matteopi.budgeting.core.planner.suggest.DemandKind
import io.github.matteopi.budgeting.core.planner.suggest.Demands
import io.github.matteopi.budgeting.core.planner.suggest.FocusBucket
import io.github.matteopi.budgeting.core.planner.suggest.FocusProfile
import io.github.matteopi.budgeting.core.planner.suggest.FocusStats
import io.github.matteopi.budgeting.core.planner.suggest.StudyInput
import io.github.matteopi.budgeting.core.planner.suggest.StudyPlanner
import io.github.matteopi.budgeting.core.planner.suggest.StudyPrefs
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import io.github.matteopi.budgeting.core.planner.suggest.TimeWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Suggested study time (docs/PLAN.md §13.4), as of Tue 20 Oct 2026 12:00 in London. */
class StudyPlannerTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)
    private fun at(day: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(oct(day).atTime(h, m), zone).toInstant()
    private val now = at(20, 12)

    private val prefs = StudyPrefs(preferred = listOf(TimeWindow(9 * 60, 18 * 60)), longestMinutes = 90, breakMinutes = 15, shortestMinutes = 30, dailyCapMinutes = 180)
    private val free = listOf(Interval(at(20, 13), at(20, 17)), Interval(at(21, 9), at(21, 12)), Interval(at(22, 14), at(22, 16)))
    private val essay = Task(id = 7, title = "EC101 essay", kind = TaskKind.DEADLINE, dueDate = oct(23), dueTime = LocalTime.of(17, 0), estimateMinutes = 360, moduleId = 3)

    private fun demands(tasks: List<Task> = listOf(essay), p: StudyPrefs = prefs, modules: List<Module> = emptyList(), studied: Map<Long, Int> = emptyMap()) =
        Demands.from(today, p, tasks, studied, emptyMap(), modules, emptyMap(), emptyMap())

    private fun suggest(
        demands: List<Demand> = demands(),
        p: StudyPrefs = prefs,
        freeTime: List<Interval> = free,
        dismissed: Set<String> = emptySet(),
        daysOff: Set<LocalDate> = emptySet(),
        used: Map<LocalDate, Int> = emptyMap(),
        focus: FocusProfile? = null,
    ) = StudyPlanner.suggest(StudyInput(now, zone, freeTime, demands, p, dismissed, daysOff, used, focus))

    private fun List<Suggestion>.spans() = map { "${it.interval.start.atZone(zone).toLocalDateTime()}–${it.interval.end.atZone(zone).toLocalTime()}" }
    private fun List<Suggestion>.minutes() = sumOf { it.interval.minutes }

    @Test
    fun `the essay checkpoint gets 375 minutes before Thursday`() {
        val result = suggest()
        assertEquals(
            listOf("2026-10-20T13:00–14:30", "2026-10-20T14:45–16:15", "2026-10-21T09:00–10:30", "2026-10-21T10:45–12:00", "2026-10-22T14:00–14:30"),
            result.spans(),
        )
        assertEquals(375, result.minutes())
        assertEquals("EC101 essay due Friday: 6 h left, 9 h free before then", result.first().reason)
        assertTrue(result.all { it.demand.taskId == 7L && it.demand.kind == DemandKind.DEADLINE })
        assertEquals("deadline:7@${at(20, 13).toEpochMilli()}", result.first().key)
    }

    @Test
    fun `caps hold per day and per week, and work already done counts`() {
        val tight = prefs.copy(dailyCapMinutes = 60)
        val result = suggest(p = tight)
        assertTrue(result.groupBy { it.interval.start.atZone(zone).toLocalDate() }.values.all { day -> day.sumOf { it.interval.minutes } <= 60 })
        assertEquals(180, result.minutes(), "60 a day on Tue, Wed and Thu")
        val weekly = suggest(p = prefs.copy(weeklyCapMinutes = 120))
        assertEquals(120, weekly.minutes())
        assertEquals(90, suggest(used = mapOf(today to 90)).filter { it.interval.start.atZone(zone).toLocalDate() == today }.minutes(), "Tue already has 90")
        assertEquals(0, suggest(demands(studied = mapOf(7L to 360))).size, "studied in full")
    }

    @Test
    fun `avoided times, days off, switched-off modules and suggestions off leave time alone`() {
        val avoid = suggest(p = prefs.copy(avoided = listOf(TimeWindow(13 * 60, 15 * 60))))
        assertTrue(avoid.none { it.interval.overlaps(Interval(at(20, 13), at(20, 15))) })
        assertTrue(suggest(daysOff = setOf(today)).none { it.interval.start.atZone(zone).toLocalDate() == today })
        assertTrue(suggest(p = prefs.copy(weeklyDaysOff = listOf(DayOfWeek.WEDNESDAY.value))).none { it.interval.start.atZone(zone).toLocalDate() == oct(21) })
        assertTrue(demands(p = prefs.copy(offModules = listOf(3))).isEmpty())
        assertTrue(suggest(p = prefs.copy(on = false)).isEmpty())
    }

    @Test
    fun `a dismissed suggestion stays dismissed and the time is offered later`() {
        val first = suggest()
        val again = suggest(dismissed = setOf(first.first().key))
        assertTrue(again.none { it.key == first.first().key })
        assertTrue(again.none { it.interval.start == at(20, 13) })
        assertEquals("2026-10-20T14:30–16:00", again.spans().first(), "the dismissed block's time isn't offered to the essay again")
    }

    @Test
    fun `the same input gives the same suggestions`() {
        assertEquals(suggest(), suggest())
    }

    @Test
    fun `a skipped block is placed again by the next run`() {
        val first = suggest()
        // Tue went by without studying: the next morning, Wed and Thu take what they can.
        val wednesday = StudyPlanner.suggest(StudyInput(at(21, 8), zone, free.drop(1), demands(), prefs))
        assertEquals(listOf("2026-10-21T09:00–10:30", "2026-10-21T10:45–12:00", "2026-10-22T14:00–15:30"), wednesday.spans())
        assertEquals(255, wednesday.minutes())
        assertTrue(first.minutes() > wednesday.minutes())
    }

    @Test
    fun `preferred times and good focus come first`() {
        val evening = Interval(at(20, 19), at(20, 21))
        val morning = Interval(at(21, 9), at(21, 11))
        val one = demands(listOf(essay.copy(estimateMinutes = 60)))
        assertEquals(at(21, 9), suggest(one, freeTime = listOf(evening, morning)).single().interval.start, "the preferred morning over the earlier evening")

        // Without preferred times: the afternoon is earliest, but the evening is when focus is best.
        val afternoon = Interval(at(20, 13), at(20, 15))
        val open = prefs.copy(preferred = emptyList())
        val good = FocusProfile(mapOf(FocusBucket(false, 18) to FocusStats(5.0, 6, null, 0)))
        assertEquals(at(20, 19), suggest(one, p = open, freeTime = listOf(afternoon, evening), focus = good).single().interval.start)
        assertEquals(at(20, 13), suggest(one, p = open.copy(learnFromRecord = false), freeTime = listOf(afternoon, evening), focus = good).single().interval.start,
            "without learning, the earliest")
        val few = FocusProfile(mapOf(FocusBucket(false, 18) to FocusStats(5.0, FocusProfile.MIN_SAMPLES - 1, null, 0)))
        assertEquals(at(20, 13), suggest(one, p = open, freeTime = listOf(afternoon, evening), focus = few).single().interval.start, "too few samples")
    }

    @Test
    fun `deadlines come first, then module targets, and blocks start on the grid`() {
        val module = Module(id = 4, code = "MA102", weeklyTargetMinutes = 120)
        val list = Demands.from(today, prefs, listOf(essay), emptyMap(), emptyMap(), listOf(module), mapOf(4L to 30), emptyMap())
        assertEquals(listOf(DemandKind.DEADLINE, DemandKind.MODULE), list.map { it.kind })
        assertEquals(90, list.last().minutes)
        assertEquals("MA102: 30 m of 2 h this week", list.last().reason)
        val odd = suggest(freeTime = listOf(Interval(at(20, 13, 7), at(20, 15))))
        assertEquals(at(20, 13, 15), odd.first().interval.start)
    }

    @Test
    fun `suggestions never overlap busy, planned or each other, and keep the caps, on random weeks`() {
        val random = Random(20261020)
        repeat(300) {
            val freeTime = (20..30).flatMap { day ->
                (0 until random.nextInt(4)).map {
                    val h = 8 + random.nextInt(12)
                    val start = at(day, h, listOf(0, 10, 15, 30, 45)[random.nextInt(5)])
                    Interval(start, start.plusSeconds(60L * (20 + random.nextInt(200))))
                }
            }
            val tasks = (1..random.nextInt(1, 5)).map { n ->
                Task(id = n.toLong(), title = "D$n", kind = TaskKind.DEADLINE, dueDate = today.plusDays(random.nextLong(0, 12)), estimateMinutes = 30 + random.nextInt(600))
            }
            val p = prefs.copy(dailyCapMinutes = 60 + random.nextInt(240), weeklyCapMinutes = if (random.nextBoolean()) null else 120 + random.nextInt(600),
                longestMinutes = 30 + random.nextInt(90), breakMinutes = random.nextInt(20))
            val result = suggest(Demands.from(today, p, tasks, emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap()), p = p, freeTime = freeTime)
            val merged = Intervals.merge(freeTime)
            assertTrue(result.all { s -> merged.any { it.intersect(s.interval) == s.interval } }, "inside free time: $result")
            assertTrue(result.zipWithNext().all { (a, b) -> !a.interval.overlaps(b.interval) }, "no two overlap")
            assertTrue(result.all { !it.interval.start.isBefore(now) })
            val byDay = result.groupBy { it.interval.start.atZone(zone).toLocalDate() }
            assertTrue(byDay.values.all { day -> day.sumOf { it.interval.minutes } <= p.dailyCapMinutes })
            p.weeklyCapMinutes?.let { cap ->
                assertTrue(result.groupBy { HabitProgress.weekStart(it.interval.start.atZone(zone).toLocalDate()) }.values.all { w -> w.sumOf { it.interval.minutes } <= cap })
            }
            assertTrue(result.all { it.interval.minutes <= p.longestMinutes })
            assertTrue(result.all { !it.interval.start.atZone(zone).toLocalDate().isAfter(maxOf(today, it.demand.latest)) })
        }
    }

    @Test
    fun `an exam's topics replace its deadline demand`() {
        val exam = Task(id = 9, title = "EC101 exam", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.EXAM, dueDate = oct(30), dueTime = LocalTime.of(9, 0), estimateMinutes = 600)
        val topics = listOf(RevisionTopic(id = 1, examTaskId = 9, name = "Elasticity", difficulty = 3))
        val list = Demands.from(today, prefs, listOf(exam), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), topics, emptyMap())
        assertEquals(listOf(DemandKind.REVISION), list.map { it.kind })
        assertEquals(60, list.single().minutes, "45 × (3 + 2) ÷ 4, rounded up to 15")
        assertEquals("EC101 exam on Fri 30 Oct: review 1 of Elasticity", list.single().reason)
        assertEquals(today, list.single().earliest, "revision has started")
        assertEquals(listOf(DemandKind.DEADLINE), Demands.from(today, prefs, listOf(exam), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap()).map { it.kind })
    }
}
