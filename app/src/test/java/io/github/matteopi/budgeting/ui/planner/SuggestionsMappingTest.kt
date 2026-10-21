package io.github.matteopi.budgeting.ui.planner

import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.suggest.Demand
import io.github.matteopi.budgeting.core.planner.suggest.DemandKind
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionsMapping
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Suggestion rows on Today (docs/PLAN.md §12.7, §13.9): their time, what for and why, and which day shows them. */
class SuggestionsMappingTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 20) // a Tuesday
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant()

    private val essay = Demand(key = "deadline:4", kind = DemandKind.DEADLINE, title = "EC101 essay", minutes = 360, latest = today.plusDays(2),
        taskId = 4, reason = "EC101 essay due Friday")

    private fun suggestion(start: Instant, minutes: Long = 90) = Suggestion("deadline:4@${start.toEpochMilli()}",
        Interval(start, start.plusSeconds(minutes * 60)), essay, "EC101 essay due Friday: 6 h left, 9 h free before then")

    @Test
    fun `a row gives the time, what for and why`() {
        val row = SuggestionsMapping.row(suggestion(at(today, 13)), today, zone)
        assertEquals("13:00–14:30", row.timeText)
        assertEquals("Today", row.dayText)
        assertEquals("EC101 essay", row.title)
        assertEquals("EC101 essay due Friday: 6 h left, 9 h free before then", row.reason)
        assertEquals("Suggested: EC101 essay, 13:00 to 14:30", row.description)
        assertEquals(suggestion(at(today, 13)).key, row.key)
        assertEquals("Tomorrow", SuggestionsMapping.row(suggestion(at(today.plusDays(1), 9)), today, zone).dayText)
    }

    @Test
    fun `Today shows the suggestions in its waking day, in time order`() {
        // A day from 08:00 to 01:00 the next morning: the 00:15 block is still today's, the next day's 09:00 is not.
        val waking = Interval(at(today, 8), at(today.plusDays(1), 1))
        val late = suggestion(at(today.plusDays(1), 0, 15), 30)
        val all = listOf(suggestion(at(today.plusDays(1), 9)), late, suggestion(at(today, 16)), suggestion(at(today, 13)))
        val rows = SuggestionsMapping.within(all, waking, today, zone)
        assertEquals(listOf("13:00–14:30", "16:00–17:30", "00:15–00:45"), rows.map { it.timeText })
    }
}
