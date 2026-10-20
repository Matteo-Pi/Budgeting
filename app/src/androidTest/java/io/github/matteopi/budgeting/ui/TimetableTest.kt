package io.github.matteopi.budgeting.ui

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import io.github.matteopi.budgeting.core.planner.timetable.TeachingWeeks
import io.github.matteopi.budgeting.data.settings.PlannerCalendarSettings
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.TestCalendar
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.plannerToday
import io.github.matteopi.budgeting.testing.seedEntry
import io.github.matteopi.budgeting.testing.seedPlannerSection
import io.github.matteopi.budgeting.testing.seedTerm
import io.github.matteopi.budgeting.testing.tagged
import io.github.matteopi.budgeting.testing.timetableBlock
import io.github.matteopi.budgeting.testing.timetableExceptions
import io.github.matteopi.budgeting.testing.typeInTag
import io.github.matteopi.budgeting.ui.planner.timetable.EntryEditTags
import io.github.matteopi.budgeting.ui.planner.today.TodayTags
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Timetable tab on screen (docs/PLAN.md §13.10, stage P2), with screenshots `timetable-*`: a class for chosen
 * weeks skipping reading week, and the week with a linked calendar event, a clash and one week cancelled.
 */
@RunWith(AndroidJUnit4::class)
class TimetableTest : UiTest() {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private var calendar: TestCalendar? = null

    @After
    fun cleanUp() {
        calendar?.delete()
        appContainer.plannerAlarms.cancel()
    }

    private fun openPlanner() {
        launchMain()
        waitForExists(clickableText("Today"), "the Today tab", HEAVY_TIMEOUT_MS)
    }

    @Test
    fun aClassForChosenWeeksIsAddedAndSkipsReadingWeek() {
        seedPlannerSection()
        val today = plannerToday()
        // This week is week 6 of an 11-week term: the reading week.
        val termStart = TeachingWeeks.weekStart(today).minusWeeks(5)
        seedTerm("Autumn", termStart, termStart.plusWeeks(11).minusDays(1))
        openPlanner()
        goToTab("Timetable")
        waitForText("Week 6 · Autumn")

        tapIcon("Add to timetable")
        typeInTag(EntryEditTags.TITLE, "EC101 Lecture")
        tapText("Chosen weeks")
        typeInTag(EntryEditTags.WEEKS, "1-5, 7-11")
        tapText(today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
        typeInTag(EntryEditTags.ROOM, "2.14")
        waitForExists(tagged(EntryEditTags.PREVIEW, "10 dates in Autumn", substring = true), "the preview of the chosen weeks")
        shot("timetable-01-editor")
        tap(hasTestTag(EntryEditTags.SAVE), "Save")

        waitForText("Week 6 · Autumn")
        val entry = blocking { appContainer.timetableRepository.getEntries() }.single()
        assertEquals(WeekPattern.TeachingWeeks((1..5).toSet() + (7..11).toSet()), entry.pattern)
        assertEquals(today.dayOfWeek, entry.weekday)
        assertEquals(LocalTime.of(9, 0), entry.start)
        assertEquals("2.14", entry.room)
        assertNeverShows(timetableBlock("EC101 Lecture"), "the lecture in reading week")
        shot("timetable-02-reading-week")

        tapIcon("Next")
        waitForText("Week 7 · Autumn")
        waitForExists(timetableBlock("EC101 Lecture"), "the lecture in week 7")
        shot("timetable-03-week-7")
    }

    @Test
    fun theWeekShowsALinkedEventAClashAndACancelledWeek() {
        seedPlannerSection()
        val today = plannerToday()
        val calendar = TestCalendar.create().also { this.calendar = it }
        calendar.addEvent("Dentist", today.atTime(13, 0), today.atTime(13, 30))
        blocking { appContainer.settingsStore.setPlannerCalendars(PlannerCalendarSettings(read = listOf(calendar.choice))) }
        val lecture = seedEntry(TimetableEntry(title = "EC101 Lecture", room = "2.14", weekday = today.dayOfWeek,
            start = LocalTime.of(9, 0), end = LocalTime.of(11, 0)))
        seedEntry(TimetableEntry(title = "EC102 Seminar", weekday = today.dayOfWeek, start = LocalTime.of(10, 0), end = LocalTime.of(11, 0)))
        openPlanner()

        // Today's agenda has the lecture and the calendar's event (the seminar falls inside the lecture's time,
        // and the day is one strip with nothing overlapping).
        for (title in listOf("EC101 Lecture", "Dentist")) {
            waitForExists(tagged(TodayTags.ON_TODAY, title), "$title on Today")
        }
        shot("timetable-04-today")

        goToTab("Timetable")
        for (title in listOf("EC101 Lecture", "EC102 Seminar", "Dentist")) {
            waitForExists(timetableBlock(title), "$title on the week")
        }
        shot("timetable-05-week-with-calendar")
        tapIcon("Clashes (1)")
        waitForText("EC101 Lecture and EC102 Seminar overlap", substring = true)
        tapInDialog("OK")

        // Cancelling this week's lecture ends the clash; putting it back brings it back.
        tap(timetableBlock("EC101 Lecture"), "the lecture")
        tapText("Cancel this week")
        waitFor("this week's lecture to be cancelled") {
            timetableExceptions().any { it.entryId == lecture && it.date == today && it.cancelled }
        }
        waitUntilGone(hasContentDescription("Clashes (1)"), "the clash warning")
        shot("timetable-06-cancelled")

        tap(timetableBlock("EC101 Lecture"), "the cancelled lecture")
        tapText("Back on this week")
        waitFor("the lecture to be back") { timetableExceptions().isEmpty() }
        waitForExists(hasContentDescription("Clashes (1)"), "the clash warning again")
    }
}
