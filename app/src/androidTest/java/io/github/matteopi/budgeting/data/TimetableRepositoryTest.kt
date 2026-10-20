package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.testing.DataTestSupport
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The timetable against the real database (docs/PLAN.md §13.5). */
@RunWith(AndroidJUnit4::class)
class TimetableRepositoryTest {
    private val container get() = DataTestSupport.container
    private val timetable get() = container.timetableRepository
    private val today get() = DataTestSupport.today()

    private val lecture get() = TimetableEntry(title = "EC101 Lecture", room = "2.14", location = "Campus", weekday = today.dayOfWeek,
        start = LocalTime.of(9, 0), end = LocalTime.of(10, 0))

    @After
    fun noAlarmLeftBehind() {
        container.plannerAlarms.cancel()
    }

    @Test
    fun anEntryIsSavedEditedAndDeletedWithUndo() = runBlocking<Unit> {
        val id = timetable.save(lecture)
        val saved = timetable.getEntry(id)!!
        assertTrue(saved.createdAt.toEpochMilli() > 0, "new entries get their creation time")
        assertEquals(lecture.copy(id = id, createdAt = saved.createdAt), saved)

        timetable.save(saved.copy(title = "EC101 Lecture (Hall B)", pattern = WeekPattern.TeachingWeeks(setOf(1, 2, 3))))
        assertEquals("EC101 Lecture (Hall B)", timetable.getEntry(id)!!.title)
        assertEquals(WeekPattern.TeachingWeeks(setOf(1, 2, 3)), timetable.getEntry(id)!!.pattern)
        assertEquals(listOf(id), timetable.observeEntries().first().map { it.id })

        timetable.cancel(id, today)
        val deleted = timetable.delete(id)!!
        assertNull(timetable.getEntry(id))
        assertTrue(timetable.getExceptions().isEmpty(), "its changes go with it")
        assertNull(timetable.delete(id), "deleting it twice does nothing")

        timetable.restore(deleted)
        assertEquals("EC101 Lecture (Hall B)", timetable.getEntry(id)?.title)
        assertEquals(listOf(today), timetable.getExceptions().map { it.date }, "Undo brings its changes back too")
    }

    @Test
    fun aWeekCanBeCancelledOrChangedAndPutBack() = runBlocking<Unit> {
        val id = timetable.save(lecture)
        val zone = container.clock.zone()
        suspend fun todays() =
            Timetable.occurrences(timetable.getEntries(), timetable.getExceptions(), emptyList(), today, today, zone, includeCancelled = true)
                .single()

        timetable.change(id, today, LocalTime.of(11, 0), LocalTime.of(12, 0), "B12")
        todays().let { o ->
            assertTrue(o.changed)
            assertEquals(LocalTime.of(11, 0), o.startTime)
            assertEquals("B12", o.room)
        }

        timetable.cancel(id, today)
        assertEquals(1, timetable.getExceptions().size, "one change per date: cancelling replaces the move")
        assertTrue(todays().cancelled)

        timetable.restoreDate(id, today)
        todays().let { o ->
            assertTrue(!o.cancelled && !o.changed)
            assertEquals(LocalTime.of(9, 0), o.startTime)
            assertEquals("2.14", o.room)
        }
        assertTrue(timetable.getExceptions().isEmpty())
    }

    @Test
    fun copyingATermAddsItsOwnEntriesForTheNextTerm() = runBlocking<Unit> {
        val autumnId = container.planRepository.addTermPeriod(TermPeriod(name = "Autumn", startDate = today.minusDays(60), endDate = today.minusDays(5)))
        val springId = container.planRepository.addTermPeriod(TermPeriod(name = "Spring", startDate = today, endDate = today.plusDays(70)))
        val terms = container.planRepository.observeTermPeriods().first()
        val autumn = terms.single { it.id == autumnId }
        val spring = terms.single { it.id == springId }
        timetable.save(lecture.copy(termId = autumnId))
        timetable.save(lecture.copy(title = "EC102 Seminar", kind = EntryKind.SEMINAR, termId = autumnId, pattern = WeekPattern.OddWeeks))
        timetable.save(lecture.copy(title = "Gym", kind = EntryKind.SPORT))
        timetable.save(lecture.copy(title = "Field trip", termId = autumnId, pattern = WeekPattern.Once(today.minusDays(20))))

        assertEquals(2, timetable.copyTerm(autumn, spring))

        val copies = timetable.getEntries().filter { it.termId == springId }
        assertEquals(setOf("EC101 Lecture", "EC102 Seminar"), copies.map { it.title }.toSet())
        assertEquals(WeekPattern.OddWeeks, copies.single { it.title == "EC102 Seminar" }.pattern)
        assertEquals(6, timetable.getEntries().size, "the autumn ones stay")

        // Deleting a term keeps its entries, for every term.
        container.planRepository.deleteTermPeriod(autumnId)
        assertTrue(timetable.getEntries().none { it.termId == autumnId })
        assertEquals(6, timetable.getEntries().size)
    }

    @Test
    fun calendarLinksAreReplacedByItemAndTheirEventsHideFromTheTimeline() = runBlocking<Unit> {
        fun link(key: String, eventId: Long) = CalendarLink(itemKey = key, calendarId = 3, eventId = eventId, uid = "planner-$key@budgeting",
            contentHash = "h$eventId")
        timetable.updateLinks(listOf(link("task:1", 10), link("task:2", 11)), emptyList())
        assertEquals(setOf(10L, 11L), timetable.observeLinkedEventIds().first())

        timetable.updateLinks(listOf(link("task:1", 12)), listOf(timetable.getLinks().single { it.itemKey == "task:2" }.id))
        val links = timetable.getLinks()
        assertEquals(listOf("task:1"), links.map { it.itemKey })
        assertEquals(12L, links.single().eventId)
        assertEquals(setOf(12L), timetable.observeLinkedEventIds().first())

        timetable.clearLinks()
        assertTrue(timetable.getLinks().isEmpty())
    }
}
