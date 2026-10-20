package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.ChunkField
import io.github.matteopi.budgeting.core.planner.day.ChunkRules
import io.github.matteopi.budgeting.core.planner.day.DayEdits
import io.github.matteopi.budgeting.core.planner.day.DayLayer
import io.github.matteopi.budgeting.core.planner.day.DaySegment
import io.github.matteopi.budgeting.core.planner.day.DaySource
import io.github.matteopi.budgeting.core.planner.day.DayTimeline
import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.PlanOutcome
import io.github.matteopi.budgeting.core.planner.model.CalendarInstance
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The day record (docs/PLAN.md §13.4): the timeline, the edits with their inverses, outcomes and rules, on Tue 20 Oct 2026. */
class DayRecordTest {

    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 10, 20)
    private fun t(h: Int, m: Int = 0): Instant = ZonedDateTime.of(date.atTime(h, m), zone).toInstant()
    private val window = Interval(t(8), t(23))
    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private fun cat(key: CategoryKey) = categories.single { it.systemKey == key }.id
    private val study get() = cat(CategoryKey.STUDY)
    private val social get() = cat(CategoryKey.SOCIAL)

    private fun record(id: Long, from: Instant, to: Instant, category: Long = study, module: Long? = null) =
        RecordChunk(id = id, start = from, end = to, categoryId = category, moduleId = module)

    private fun lecture(h: Int, h2: Int, kind: EntryKind = EntryKind.LECTURE) = Occurrence(
        entryId = 1, date = date, start = t(h), end = t(h2), startTime = t(h).atZone(zone).toLocalTime(), endTime = t(h2).atZone(zone).toLocalTime(),
        title = "EC101 Lecture", kind = kind, moduleId = 3, location = "Campus", room = "LT1", colorArgb = null,
    )

    // ---- The timeline --------------------------------------------------------------------------

    @Test
    fun `records win, then plans over classes over events, and the gaps split at now`() {
        val records = listOf(record(1, t(9), t(10)))
        val plan = PlanBlock(id = 7, start = t(13), end = t(15), categoryId = study, moduleId = 3)
        val event = CalendarInstance(eventId = 5, calendarId = 1, title = "Dentist", location = "", start = t(14), end = t(16, 30),
            allDay = false, busy = true, colorArgb = null)
        val allDay = event.copy(eventId = 6, allDay = true, start = t(0), end = t(23, 59))
        val freeEvent = event.copy(eventId = 8, busy = false, start = t(20), end = t(21))
        val segments = DayTimeline.build(window, now = t(14), records, listOf(plan), listOf(lecture(9, 11)), listOf(event, allDay, freeEvent))

        fun describe(s: DaySegment): String {
            val span = "${s.interval.start.atZone(zone).toLocalTime()}-${s.interval.end.atZone(zone).toLocalTime()}"
            return when (s) {
                is DaySegment.Recorded -> "recorded $span"
                is DaySegment.Waiting -> "waiting ${s.source.key} $span"
                is DaySegment.Unrecorded -> "unrecorded $span"
                is DaySegment.Planned -> "planned ${s.source.key} $span"
                is DaySegment.Free -> "free $span"
            }
        }
        assertEquals(
            listOf(
                "unrecorded 08:00-09:00",
                "recorded 09:00-10:00",
                "waiting entry:1:2026-10-20 10:00-11:00",
                "unrecorded 11:00-13:00",
                "waiting plan:7 13:00-14:00",
                "planned plan:7 14:00-15:00",
                "planned event:5:${t(14).toEpochMilli()} 15:00-16:30",
                "free 16:30-23:00",
            ),
            segments.map(::describe),
        )
        assertEquals(2 to 2, DayTimeline.counts(segments))
    }

    @Test
    fun `a confirmed or skipped source leaves no waiting part, and the app's own events are left out`() {
        val plan = PlanBlock(id = 7, start = t(13), end = t(14), categoryId = study)
        val confirmed = record(1, t(13), t(13, 30)).copy(planBlockId = 7)
        val skipped = plan.copy(id = 8, start = t(15), end = t(16), outcomeOverride = Outcome.SKIPPED)
        val mine = CalendarInstance(eventId = 9, calendarId = 1, title = "Call home", location = "", start = t(17), end = t(17, 30),
            allDay = false, busy = true, colorArgb = null)
        val segments = DayTimeline.build(window, now = t(20), listOf(confirmed), listOf(plan, skipped), emptyList(), listOf(mine), appEventIds = setOf(9L))
        assertTrue(segments.none { it is DaySegment.Waiting })
        assertEquals(listOf(Interval(t(8), t(13)), Interval(t(13, 30), t(20))), segments.filterIsInstance<DaySegment.Unrecorded>().map { it.interval })
    }

    @Test
    fun `everything is clipped to the window and never overlaps`() {
        val late = record(1, t(22), t(23, 30))
        val segments = DayTimeline.build(window, now = t(23, 30), listOf(late), emptyList(), listOf(lecture(7, 9)), emptyList())
        assertEquals(Interval(t(22), t(23)), segments.last().interval)
        assertEquals(Interval(t(8), t(9)), assertIs<DaySegment.Waiting>(segments.first()).interval)
        segments.zipWithNext().forEach { (a, b) -> assertTrue(!b.interval.start.isBefore(a.interval.end), "$a then $b") }
    }

    // ---- The edits -----------------------------------------------------------------------------

    @Test
    fun `a new chunk wins and trims, splits or deletes what it overlaps`() {
        val existing = listOf(record(1, t(9), t(12)), record(2, t(12), t(13), social), record(3, t(13), t(13, 3)))
        val plan = assertNotNull(DayEdits.place(existing, record(0, t(10), t(12, 30), cat(CategoryKey.REST))))
        assertEquals(listOf(Interval(t(10), t(12, 30))), plan.inserts.map { Interval(it.start, it.end) })
        assertEquals(
            listOf(1L to Interval(t(9), t(10)), 2L to Interval(t(12, 30), t(13))),
            plan.updates.map { (before, after) -> before.id to Interval(after.start, after.end) },
        )
        assertTrue(plan.deletes.isEmpty())

        val inside = assertNotNull(DayEdits.place(existing, record(0, t(10), t(11), social)))
        assertEquals(listOf(Interval(t(10), t(11)), Interval(t(11), t(12))), inside.inserts.map { Interval(it.start, it.end) }, "the right half is new")
        assertEquals(listOf(Interval(t(9), t(10))), inside.updates.map { Interval(it.second.start, it.second.end) })

        val over = assertNotNull(DayEdits.place(existing, record(0, t(11, 58), t(13, 5))))
        assertEquals(listOf(2L, 3L), over.deletes.map { it.id })
        assertEquals(listOf(Interval(t(9), t(11, 58))), over.updates.map { Interval(it.second.start, it.second.end) })

        assertEquals(listOf(2L), DayEdits.overlapped(existing, record(0, t(12, 15), t(12, 45))).map { it.id })
        assertNull(DayEdits.place(existing, record(0, t(7), t(8, 2)), window), "2 minutes left inside the window")
    }

    @Test
    fun `an edited chunk updates in place`() {
        val existing = listOf(record(1, t(9), t(10)), record(2, t(10), t(11)))
        val plan = assertNotNull(DayEdits.place(existing, existing[0].copy(end = t(10, 30), note = "Library")))
        assertEquals(listOf(existing[0] to existing[0].copy(end = t(10, 30), note = "Library"), existing[1] to existing[1].copy(start = t(10, 30))), plan.updates)
        assertTrue(plan.inserts.isEmpty())
    }

    @Test
    fun `split, move a boundary and merge`() {
        val a = record(1, t(9), t(11))
        val b = record(2, t(11), t(12), social)
        val c = record(3, t(14), t(15))
        val all = listOf(a, b, c)

        val split = assertNotNull(DayEdits.split(a, t(10, 7), zone))
        assertEquals(listOf(a to a.copy(end = t(10))), split.updates)
        assertEquals(listOf(a.copy(id = 0, start = t(10))), split.inserts)
        assertNull(DayEdits.split(a, t(9, 1), zone), "snapped onto the start")

        val shared = assertNotNull(DayEdits.moveBoundary(all, a, b, t(11, 20), window, zone))
        assertEquals(listOf(a to a.copy(end = t(11, 15)), b to b.copy(start = t(11, 15))), shared.updates)
        val tooFar = assertNotNull(DayEdits.moveBoundary(all, a, b, t(13), window, zone))
        assertEquals(t(11, 45), tooFar.updates.last().second.start, "b keeps 15 minutes")
        val intoGap = assertNotNull(DayEdits.moveBoundary(all, b, null, t(16), window, zone))
        assertEquals(listOf(b to b.copy(end = t(14))), intoGap.updates, "up to the next chunk")
        val back = assertNotNull(DayEdits.moveBoundary(all, null, c, t(7), window, zone))
        assertEquals(listOf(c to c.copy(start = t(12))), back.updates, "back to the previous chunk")
        assertNull(DayEdits.moveBoundary(all, a, c, t(13), window, zone), "a and c don't share an edge")

        val merged = assertNotNull(DayEdits.merge(all, b, c))
        assertEquals(listOf(b to b.copy(end = t(15))), merged.updates)
        assertEquals(listOf(c), merged.deletes)
        assertNull(DayEdits.merge(all, a, c), "b lies between them")
    }

    @Test
    fun `confirming copies the source and fills only unrecorded time up to now`() {
        val records = listOf(record(1, t(10), t(10, 30), social))
        val segments = DayTimeline.build(window, now = t(10, 45), records, emptyList(), listOf(lecture(9, 11)), emptyList())
        val waiting = segments.filterIsInstance<DaySegment.Waiting>()
        assertEquals(listOf(Interval(t(9), t(10)), Interval(t(10, 30), t(10, 45))), waiting.map { it.interval })
        val all = assertNotNull(DayEdits.confirmAll(records, segments, categories, t(10, 45)))
        assertEquals(listOf(Interval(t(9), t(10)), Interval(t(10, 30), t(10, 45))), all.inserts.map { Interval(it.start, it.end) })
        all.inserts.forEach {
            assertEquals(cat(CategoryKey.CLASS), it.categoryId)
            assertEquals(3L, it.moduleId)
            assertEquals("entry:1:2026-10-20", it.sourceKey)
            assertEquals(RecordOrigin.CONFIRMED, it.origin)
        }
        val plan = PlanBlock(id = 4, start = t(12), end = t(13), categoryId = study, moduleId = 3, taskId = 9)
        val fromPlan = assertNotNull(DayEdits.recordFor(DaySource.Plan(plan), categories))
        assertEquals(listOf(study, 3L, 9L, 4L), listOf(fromPlan.categoryId, fromPlan.moduleId, fromPlan.taskId, fromPlan.planBlockId))
        assertEquals(cat(CategoryKey.EXERCISE), DayEdits.recordFor(DaySource.Class(lecture(9, 10, EntryKind.SPORT)), categories)?.categoryId)
        assertEquals(cat(CategoryKey.SOCIAL), DayEdits.categoryId(categories, DayEdits.classCategory(EntryKind.SOCIETY)))
    }

    @Test
    fun `the inverse gives back the rows`() {
        val existing = listOf(record(1, t(9), t(12)), record(2, t(12), t(13), social))
        val plan = assertNotNull(DayEdits.place(existing, record(0, t(10), t(11), social)))
        // As the repository applies it: inserts get ids.
        var nextId = 10L
        val applied = plan.copy(inserts = plan.inserts.map { it.copy(id = nextId++) })
        fun apply(rows: List<RecordChunk>, p: EditPlan<RecordChunk>): List<RecordChunk> {
            val deleted = p.deletes.map { it.id }.toSet()
            val updated = p.updates.associate { it.second.id to it.second }
            return (rows.filter { it.id !in deleted }.map { updated[it.id] ?: it } + p.inserts).sortedBy { it.id }
        }
        val after = apply(existing, applied)
        assertEquals(existing.sortedBy { it.id }, apply(after, applied.inverse()))
    }

    @Test
    fun `the layer is records before now, plans from now`() {
        assertEquals(DayLayer.RECORD, DayEdits.layerOf(t(9), t(10)))
        assertEquals(DayLayer.PLAN, DayEdits.layerOf(t(10), t(10)))
    }

    // ---- Outcomes and rules --------------------------------------------------------------------

    @Test
    fun `a block half covered by its study is partly done`() {
        val block = PlanBlock(id = 1, start = t(14), end = t(15, 30), categoryId = study, moduleId = 3)
        val records = listOf(record(1, t(14), t(14, 50), study, module = 3), record(2, t(14, 50), t(15, 30), social))
        assertEquals(Outcome.PARTLY, PlanOutcome.of(block, records, t(16)))
        assertNull(PlanOutcome.of(block, records, t(15)), "not over yet")
        assertEquals(Outcome.DONE, PlanOutcome.of(block, listOf(record(1, t(14), t(15, 20), study, module = 3)), t(16)))
        assertEquals(Outcome.SKIPPED, PlanOutcome.of(block, listOf(record(1, t(14), t(14, 50), study, module = 4)), t(16)), "another module")
        assertEquals(Outcome.DONE, PlanOutcome.of(block.copy(outcomeOverride = Outcome.DONE), emptyList(), t(16)))
    }

    @Test
    fun `a chunk needs times, a category and a fitting focus`() {
        val studyCategory: TimeCategory = categories.single { it.id == study }
        val socialCategory: TimeCategory = categories.single { it.id == social }
        assertTrue(ChunkRules.errors(t(9), t(10), studyCategory, 4).isEmpty())
        assertEquals(setOf(ChunkField.TIME), ChunkRules.errors(t(10), t(9), studyCategory, null).keys)
        assertEquals(setOf(ChunkField.TIME), ChunkRules.errors(t(9), t(9, 3), studyCategory, null).keys)
        assertEquals(setOf(ChunkField.CATEGORY), ChunkRules.errors(t(9), t(10), null, null).keys)
        assertEquals(setOf(ChunkField.FOCUS), ChunkRules.errors(t(9), t(10), socialCategory, 3).keys)
        assertEquals(setOf(ChunkField.FOCUS), ChunkRules.errors(t(9), t(10), studyCategory, 6).keys)
    }

    @Test
    fun `seeding adds only the defaults that are missing`() {
        assertEquals(11, DefaultTimeCategories.missing(emptyList()).size)
        val mine = listOf(TimeCategory(id = 1, name = "Revision", colorArgb = 1, systemKey = CategoryKey.STUDY, sortOrder = 4))
        val added = DefaultTimeCategories.missing(mine)
        assertEquals(10, added.size)
        assertTrue(added.none { it.systemKey == CategoryKey.STUDY })
        assertEquals((5..14).toList(), added.map { it.sortOrder })
        assertEquals(CategoryKey.entries.toSet(), DefaultTimeCategories.all.mapNotNull { it.systemKey }.toSet())
    }
}
