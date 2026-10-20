package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.planner.day.DayLayer
import io.github.matteopi.budgeting.core.planner.day.DayTimeline
import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.freetime.FreeDay
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.domain.planner.CalendarState
import io.github.matteopi.budgeting.domain.planner.DayModel
import io.github.matteopi.budgeting.ui.planner.day.ChunkForm
import io.github.matteopi.budgeting.ui.planner.day.ChunkState
import io.github.matteopi.budgeting.ui.planner.day.DayMapping
import io.github.matteopi.budgeting.ui.planner.today.TodayMapping
import io.github.matteopi.budgeting.widget.planner.PlannerWidgetState
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.data.settings.TimerState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The day editor's canvas, the chunk form, Today's agenda and the widget (docs/PLAN.md §13.9), on Tue 20 Oct 2026. */
class DayMappingTest {

    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 10, 20)
    private fun t(h: Int, m: Int = 0): Instant = ZonedDateTime.of(date.atTime(h, m), zone).toInstant()
    private val window = Interval(t(8), ZonedDateTime.of(date.plusDays(1).atStartOfDay(), zone).toInstant())
    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private val study = categories.single { it.systemKey == CategoryKey.STUDY }
    private val social = categories.single { it.systemKey == CategoryKey.SOCIAL }
    private val ec101 = Module(id = 3, code = "EC101", colorArgb = 0xFF3F7CAC)

    private val lecture = Occurrence(1, date, t(9), t(10), LocalTime.of(9, 0), LocalTime.of(10, 0), "EC101 Lecture", EntryKind.LECTURE, 3, "Campus",
        "LT1", null)

    private fun model(
        now: Instant,
        records: List<RecordChunk> = emptyList(),
        plans: List<PlanBlock> = emptyList(),
        occurrences: List<Occurrence> = listOf(lecture),
        review: DayReview? = null,
    ) = DayModel(
        date = date,
        window = window,
        waking = window,
        now = now,
        zone = zone,
        segments = DayTimeline.build(window, now, records, plans, occurrences, emptyList()),
        records = records,
        plans = plans,
        categories = categories,
        occurrences = occurrences,
        instances = emptyList(),
        free = FreeDay(listOf(Interval(t(10), t(23)))),
        unplannedMinutes = 12 * 60,
        freeLeftMinutes = 7 * 60,
        review = review,
        calendar = CalendarState.Off,
    )

    private val records = listOf(
        RecordChunk(id = 1, start = t(10), end = t(11), categoryId = study.id, moduleId = 3, origin = RecordOrigin.TIMER),
        RecordChunk(id = 2, start = t(11), end = t(12, 30), categoryId = social.id, note = "Lunch with Sam"),
    )
    private val plan = PlanBlock(id = 5, start = t(15), end = t(16), categoryId = study.id, moduleId = 3)

    @Test
    fun `segments carry their state, words, colour and place on the canvas`() {
        val segments = DayMapping.segments(model(t(13), records, listOf(plan)), listOf(ec101), review = true)
        assertEquals(
            listOf(
                ChunkState.NOT_RECORDED, ChunkState.TO_CONFIRM, ChunkState.DONE, ChunkState.DONE, ChunkState.NOT_RECORDED,
                ChunkState.FREE, ChunkState.PLANNED, ChunkState.FREE,
            ),
            segments.map { it.state },
        )
        val waiting = segments[1]
        assertEquals("EC101 Lecture", waiting.title)
        assertEquals("Lecture · LT1 · Campus", waiting.detail)
        assertEquals(0xFF3F7CAC, waiting.colorArgb, "the module's colour")
        assertEquals(60, waiting.startMinute)
        assertEquals(120, waiting.endMinute)
        assertTrue(waiting.needsReview)
        val studied = segments[2]
        assertEquals("Study · EC101", studied.title)
        assertEquals("Timer", studied.detail)
        assertEquals(1L, studied.recordId)
        assertEquals("Lunch with Sam", segments[3].detail)
        assertEquals("Study · EC101, Planned, 15:00 to 16:00, Planned", segments[6].description)
        assertEquals(5L, segments[6].planId)
        assertFalse(segments[5].needsReview, "free time ahead isn't for the review")
        assertEquals(listOf("09:00–10:00", "10:00–11:00"), segments.subList(1, 3).map { it.timeText })
    }

    @Test
    fun `boundaries are each record's edges, a shared edge once, and future plans' edges`() {
        val boundaries = DayMapping.boundaries(model(t(13), records, listOf(plan)))
        assertEquals(
            listOf("RECORD:-:1 10:00", "RECORD:1:2 11:00", "RECORD:2:- 12:30", "PLAN:-:5 15:00", "PLAN:5:- 16:00"),
            boundaries.map { "${it.key} ${it.label}" },
        )
        assertEquals(DayLayer.PLAN, boundaries.last().layer)
        assertEquals(8 * 60, boundaries.last().minute)
        assertTrue(DayMapping.boundaries(model(t(17), records, listOf(plan))).none { it.layer == DayLayer.PLAN }, "a plan that has ended isn't dragged")
    }

    @Test
    fun `hour lines follow the local clock, and the strip shows what was planned in the past`() {
        val hours = DayMapping.hours(window, zone)
        assertEquals("08:00", hours.first().label)
        assertEquals("00:00", hours.last().label)
        assertEquals(17, hours.size)
        val strip = DayMapping.plannedStrip(model(t(15, 30), records, listOf(plan)), listOf(ec101))
        assertEquals(listOf(60 to 120, 7 * 60 to 7 * 60 + 30), strip.map { it.startMinute to it.endMinute }, "the lecture, and the plan up to now")
        // The clocks go back on 25 Oct: the window has a 25th hour, and 01:00 twice.
        val long = Interval(ZonedDateTime.of(LocalDate.of(2026, 10, 25).atTime(0, 0), zone).toInstant(),
            ZonedDateTime.of(LocalDate.of(2026, 10, 26).atTime(0, 0), zone).toInstant())
        assertEquals(26, DayMapping.hours(long, zone).size)
        assertEquals(2, DayMapping.hours(long, zone).count { it.label == "01:00" })
    }

    @Test
    fun `the review banner counts what is left`() {
        assertEquals("2 not recorded · 1 to confirm", DayMapping.reviewBanner(model(t(13), records)))
        val done = listOf(
            RecordChunk(id = 1, start = t(8), end = t(9), categoryId = social.id),
            RecordChunk(id = 2, start = t(9), end = t(13), categoryId = study.id),
        )
        assertNull(DayMapping.reviewBanner(model(t(13), done)))
    }

    @Test
    fun `typed times land on the window's clock`() {
        val form = ChunkForm(start = LocalTime.of(23, 30), end = LocalTime.of(0, 30), categoryId = study.id)
        assertEquals(t(23, 30), form.startInstant(window, zone))
        assertEquals(window.end.plusSeconds(1_800), form.endInstant(window, zone), "an end before the start is the next day")
        val fixed = Interval(t(4), t(4).plusSeconds(86_400))
        assertEquals(t(2).plusSeconds(86_400), ChunkForm.instantOf(LocalTime.of(2, 0), fixed, zone), "02:00 of a 04:00 day is the next morning")
        assertTrue(form.errors(window, zone, categories).isEmpty())
        assertEquals(1, ChunkForm(start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)).errors(window, zone, categories).size, "no category")
        val record = ChunkForm.of(records[0], zone).copy(note = "  Problem set ")
        assertEquals(records[0].copy(note = "Problem set"), record.toRecord(window, zone))
        val newPlan = ChunkForm.new(t(15), t(16), zone, study.id, 3).toPlan(window, zone)
        assertEquals(PlanBlock(start = t(15), end = t(16), categoryId = study.id, moduleId = 3), newPlan)
    }

    @Test
    fun `today's agenda leaves out short gaps and sums the free time`() {
        val short = listOf(RecordChunk(id = 1, start = t(8), end = t(8, 50), categoryId = study.id))
        val agenda = TodayMapping.agenda(model(t(13), short + records.drop(1).map { it.copy(start = t(10)) }), listOf(ec101))
        assertTrue(agenda.none { it.state == ChunkState.NOT_RECORDED && it.timeText == "08:50–09:00" }, "10 minutes not recorded")
        assertEquals("EC101 Lecture", agenda.first { it.state == ChunkState.TO_CONFIRM }.title)
        assertEquals("13 h free", TodayMapping.freeSummary(model(t(13))).substringBefore(" ("))
        assertEquals("13 h free (1 h planned)", TodayMapping.freeSummary(model(t(13)).copy(unplannedMinutes = 12 * 60)))
    }

    @Test
    fun `finish yesterday is offered while there is something to review`() {
        assertTrue(TodayMapping.offerFinishYesterday(model(t(23, 59), records)))
        assertTrue(TodayMapping.offerFinishYesterday(model(t(23, 59))), "the lecture waits to be confirmed")
        assertFalse(TodayMapping.offerFinishYesterday(model(t(23, 59), occurrences = emptyList())), "nothing happened")
        assertFalse(TodayMapping.offerFinishYesterday(model(t(23, 59), records, review = DayReview(date, finishedAt = t(23)))))
        assertFalse(TodayMapping.offerFinishYesterday(null))
    }

    @Test
    fun `the widget shows the class on now or next, the free time, tasks and the timer`() {
        val tasks = listOf(
            Task(id = 1, title = "Hand in form", dueDate = date, dueTime = LocalTime.of(17, 0)),
            Task(id = 2, title = "Laundry", dueDate = date.minusDays(2)),
            Task(id = 3, title = "Later", dueDate = date.plusDays(3)),
        )
        val before = PlannerWidgetState.of(t(8, 30), date, zone, listOf(lecture), 300, tasks, categories, listOf(ec101), null)
        assertEquals("Next: EC101 Lecture at 09:00 · LT1", before.classLine)
        assertEquals("5 h free left today", before.freeLine)
        assertEquals(listOf("Laundry", "Hand in form"), before.tasks.map { it.title }, "overdue first; not later ones")
        assertEquals(study.id, before.startCategoryId)
        assertNull(before.timerLine)
        val during = PlannerWidgetState.of(t(9, 30), date, zone, listOf(lecture), 300, tasks, categories, listOf(ec101),
            TimerState(t(9).toEpochMilli(), study.id, moduleId = 3))
        assertEquals("Now: EC101 Lecture · LT1", during.classLine)
        assertEquals("Study · EC101 · 30 m", during.timerLine)
        assertNull(PlannerWidgetState.of(t(11), date, zone, listOf(lecture), 0, emptyList(), categories, emptyList(), null).classLine)
    }
}
