package io.github.matteopi.budgeting.data

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.matteopi.budgeting.core.planner.calendar.CalendarSync
import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.data.settings.PlannerCalendarSettings
import io.github.matteopi.budgeting.domain.planner.CalendarState
import io.github.matteopi.budgeting.domain.planner.instances
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.TestCalendar
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's calendars through the real calendar provider (docs/PLAN.md §13.7), on a [TestCalendar]: reading, and
 * writing back, which must only ever touch the events the app wrote.
 */
@RunWith(AndroidJUnit4::class)
class CalendarProviderTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val container get() = DataTestSupport.container
    private val zone get() = container.clock.zone()
    private val today get() = DataTestSupport.today()
    private val tomorrow get() = today.plusDays(1)
    private lateinit var calendar: TestCalendar

    @Before
    fun createCalendar() {
        calendar = TestCalendar.create()
    }

    @After
    fun removeCalendar() {
        // Writing back off first, so a sync queued by the test's own writes finds nothing to do.
        runBlocking { container.settingsStore.setPlannerCalendars(PlannerCalendarSettings()) }
        calendar.delete()
        container.plannerAlarms.cancel()
    }

    private suspend fun sync() = assertTrue(container.plannerCalendarSync.sync(), "the sync could write")

    @Test
    fun theAppReadsTheChosenCalendarsEventsOffline() = runBlocking<Unit> {
        val access = container.calendarAccess
        val info = access.calendars().single { it.id == calendar.id }
        assertEquals(TestCalendar.NAME, info.name)
        assertEquals(TestCalendar.ACCOUNT, info.accountName)
        assertTrue(info.writable)
        assertEquals(TestCalendar.COLOUR, info.colorArgb)

        val dentist = calendar.addEvent("Dentist", tomorrow.atTime(13, 0), tomorrow.atTime(13, 30))
        calendar.addAllDay("Field trip", tomorrow)
        calendar.addEvent("Next week", tomorrow.plusDays(7).atTime(9, 0), tomorrow.plusDays(7).atTime(10, 0))
        val from = tomorrow.atStartOfDay(zone).toInstant()
        val to = tomorrow.plusDays(1).atStartOfDay(zone).toInstant()

        val instances = access.instances(from, to, setOf(calendar.id), zone)
        assertEquals(setOf("Dentist", "Field trip"), instances.map { it.title }.toSet())
        val timed = instances.single { it.title == "Dentist" }
        assertEquals(dentist, timed.eventId)
        assertFalse(timed.allDay)
        assertEquals(tomorrow.atTime(13, 0).atZone(zone).toInstant(), timed.start)
        assertTrue(timed.busy)
        val allDay = instances.single { it.title == "Field trip" }
        assertTrue(allDay.allDay)
        assertEquals(tomorrow.atStartOfDay(zone).toInstant(), allDay.start, "an all-day event is the local day")
        assertEquals(to, allDay.end)

        // Through the Planner: nothing until the calendar is chosen, then its events.
        assertEquals(CalendarState.Off, container.plannerCalendar.observe(from, to).first())
        container.settingsStore.setPlannerCalendars(PlannerCalendarSettings(read = listOf(calendar.choice)))
        val state = withTimeout(10_000) { container.plannerCalendar.observe(from, to).first { it is CalendarState.Ready } }
        assertEquals(setOf("Dentist", "Field trip"), state.instances.map { it.title }.toSet())
    }

    @Test
    fun aRepeatingEventIsReadWithItsMovedDayAndWithoutItsCancelledOne() = runBlocking<Unit> {
        val start = tomorrow.atTime(8, 0)
        val gym = calendar.addDaily("Gym", start, days = 3)
        calendar.moveOccurrence(gym, start.plusDays(1), start.plusDays(1).withHour(9))
        calendar.cancelOccurrence(gym, start.plusDays(2))

        val instances = container.calendarAccess.instances(
            tomorrow.atStartOfDay(zone).toInstant(), tomorrow.plusDays(4).atStartOfDay(zone).toInstant(), setOf(calendar.id), zone,
        ).filter { it.title == "Gym" }

        assertEquals(
            listOf(start, start.plusDays(1).withHour(9)).map { it.atZone(zone).toInstant() },
            instances.map { it.start },
            "the first day as set, the second moved to 09:00, the third cancelled",
        )
        assertEquals(start.plusDays(1).withHour(10).atZone(zone).toInstant(), instances[1].end, "the moved day keeps the hour")
    }

    @Test
    fun writingBackAddsUpdatesAndRemovesOnlyTheAppsOwnEvents() = runBlocking<Unit> {
        val tasks = container.taskRepository
        val timetable = container.timetableRepository
        val dentist = calendar.addEvent("Dentist", tomorrow.atTime(13, 0), tomorrow.atTime(13, 30))
        container.settingsStore.setPlannerCalendars(PlannerCalendarSettings(writeTo = calendar.choice))
        val taskId = tasks.save(Task(title = "Call home", dueDate = tomorrow, dueTime = LocalTime.of(18, 0), lengthMinutes = 20))
        tasks.save(Task(title = "No time", dueDate = tomorrow))

        sync()
        val written = calendar.appEvents().single()
        assertEquals("Call home", written.title)
        assertEquals(tomorrow.atTime(18, 0).atZone(zone).toInstant(), written.start)
        assertEquals(tomorrow.atTime(18, 20).atZone(zone).toInstant(), written.end)
        assertEquals("planner-task-$taskId@budgeting", written.uid)
        assertEquals(CalendarSync.DESCRIPTION, written.description)
        assertEquals(listOf("task:$taskId"), timetable.getLinks().map { it.itemKey })

        // Syncing again changes nothing; an edit changes the same event.
        sync()
        assertEquals(listOf(written.id), calendar.appEvents().map { it.id })
        tasks.save(tasks.getTask(taskId)!!.copy(title = "Call Mum"))
        sync()
        assertEquals(listOf(written.id to "Call Mum"), calendar.appEvents().map { it.id to it.title })

        // Links lost (a restore, a new phone): the event is found again by its UID, not written twice.
        timetable.clearLinks()
        sync()
        assertEquals(1, calendar.appEvents().size)
        assertEquals(listOf(written.id), timetable.getLinks().map { it.eventId })

        // A link pointing at one of the user's own events (restored from another phone) never touches it.
        timetable.updateLinks(
            listOf(CalendarLink(itemKey = "task:999", calendarId = calendar.id, eventId = dentist, uid = "planner-task-999@budgeting", contentHash = "x")),
            emptyList(),
        )
        sync()
        assertEquals("Dentist", calendar.events().single { it.id == dentist }.title)

        // Done: its event goes, and the user's own stays.
        tasks.complete(tasks.getTask(taskId)!!)
        sync()
        assertTrue(calendar.appEvents().isEmpty())
        assertEquals(listOf("Dentist"), calendar.events().map { it.title })
        assertTrue(timetable.getLinks().isEmpty())
    }

    @Test
    fun theTimetableIsWrittenOnlyWhenAskedAndRemoveTakesEverythingTheAppAdded() = runBlocking<Unit> {
        val timetable = container.timetableRepository
        calendar.addEvent("Dentist", tomorrow.atTime(13, 0), tomorrow.atTime(13, 30))
        val entryId = timetable.save(TimetableEntry(title = "EC101 Lecture", room = "2.14", location = "Campus", weekday = tomorrow.dayOfWeek,
            start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)))
        container.settingsStore.setPlannerCalendars(PlannerCalendarSettings(writeTo = calendar.choice))
        sync()
        assertTrue(calendar.appEvents().isEmpty(), "classes only when asked")

        container.settingsStore.setPlannerCalendars(PlannerCalendarSettings(writeTo = calendar.choice, writeTimetable = true))
        sync()
        // No term dates: every week, as far as the sync looks ahead (120 days).
        val expected = Timetable.occurrences(timetable.getEntries(), emptyList(), emptyList(), today, today.plusDays(120), zone)
        val written = calendar.appEvents()
        assertEquals(expected.map { it.start }, written.map { it.start })
        assertTrue(written.size >= 17, "about 17 weeks; have ${written.size}")
        assertTrue(written.all { it.title == "EC101 Lecture" && it.location == "2.14, Campus" })
        assertEquals("planner-entry-$entryId-$tomorrow@budgeting", written.first().uid)

        // Cancelling a week takes its event away.
        timetable.cancel(entryId, tomorrow)
        sync()
        assertEquals(written.size - 1, calendar.appEvents().size)
        assertTrue(calendar.appEvents().none { it.start == written.first().start })

        // Writing back off, then *Remove what the app added*: only the user's own event is left.
        container.settingsStore.setPlannerCalendars(PlannerCalendarSettings())
        assertEquals(written.size - 1, container.plannerCalendarSync.removeAll())
        assertEquals(listOf("Dentist"), calendar.events().map { it.title })
        assertTrue(timetable.getLinks().isEmpty())
    }
}
