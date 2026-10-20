package io.github.matteopi.budgeting.ui.planner.timetable

import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import io.github.matteopi.budgeting.core.planner.timetable.EntryField
import io.github.matteopi.budgeting.core.planner.timetable.EntryRules
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.core.planner.timetable.WeekList
import io.github.matteopi.budgeting.core.planner.timetable.WeekListResult
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** The week patterns as the editor offers them. */
enum class PatternChoice { EVERY_WEEK, CHOSEN_WEEKS, ODD_WEEKS, EVEN_WEEKS, ONCE }

/**
 * The timetable entry editor's fields (docs/PLAN.md §13.9). Pure: [toEntry] gives the entry to save, [errors] what
 * stops it, and [preview] the dates it lands on this term.
 */
data class EntryForm(
    val id: Long = 0,
    val title: String = "",
    val kind: EntryKind = EntryKind.LECTURE,
    val moduleId: Long? = null,
    val location: String = "",
    val room: String = "",
    val contact: String = "",
    val colorArgb: Long? = null,
    val note: String = "",
    val weekday: DayOfWeek = DayOfWeek.MONDAY,
    val start: LocalTime? = LocalTime.of(9, 0),
    val end: LocalTime? = LocalTime.of(10, 0),
    val pattern: PatternChoice = PatternChoice.EVERY_WEEK,
    val weeksText: String = "",
    val onceDate: LocalDate? = null,
    val termId: Long? = null,
    val inHolidays: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Instant = Instant.EPOCH,
) {
    private val weeks: WeekListResult get() = WeekList.parse(weeksText)

    fun toEntry(): TimetableEntry = TimetableEntry(
        id = id,
        title = title.trim(),
        kind = kind,
        moduleId = moduleId,
        location = location.trim(),
        room = room.trim(),
        contact = contact.trim(),
        colorArgb = colorArgb,
        note = note.trim(),
        weekday = if (pattern == PatternChoice.ONCE) onceDate?.dayOfWeek ?: weekday else weekday,
        start = start ?: LocalTime.of(9, 0),
        end = end ?: LocalTime.of(10, 0),
        pattern = when (pattern) {
            PatternChoice.EVERY_WEEK -> WeekPattern.EveryWeek
            PatternChoice.CHOSEN_WEEKS -> WeekPattern.TeachingWeeks((weeks as? WeekListResult.Ok)?.weeks ?: emptySet())
            PatternChoice.ODD_WEEKS -> WeekPattern.OddWeeks
            PatternChoice.EVEN_WEEKS -> WeekPattern.EvenWeeks
            PatternChoice.ONCE -> WeekPattern.Once(onceDate ?: LocalDate.MIN)
        },
        termId = if (pattern == PatternChoice.ONCE) null else termId,
        inHolidays = pattern == PatternChoice.EVERY_WEEK && inHolidays,
        archived = archived,
        createdAt = createdAt,
    )

    val errors: Map<EntryField, String>
        get() {
            val errors = LinkedHashMap(EntryRules.errors(toEntry()))
            if (start == null || end == null) errors[EntryField.TIME] = "Type a start and an end time."
            if (pattern == PatternChoice.CHOSEN_WEEKS) (weeks as? WeekListResult.Error)?.let { errors[EntryField.WEEKS] = it.message }
            if (pattern == PatternChoice.ONCE && onceDate == null) errors[EntryField.DATE] = "Choose the date."
            return errors
        }

    /**
     * Where the entry lands, for the editor: "10 dates in Autumn: 28 Sep, 5 Oct, 12 Oct, …" over the term holding
     * [today] (or the next one), "Every Monday" without term dates, or null for a once-only entry.
     */
    fun preview(terms: List<TermPeriod>, today: LocalDate): String? {
        if (pattern == PatternChoice.ONCE) return null
        val entry = toEntry()
        if (terms.isEmpty()) {
            return if (entry.pattern == WeekPattern.EveryWeek) "Every ${weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}" else null
        }
        val term = (termId?.let { id -> terms.firstOrNull { it.id == id } })
            ?: Regimes.termContaining(today, terms)
            ?: terms.filter { it.startDate.isAfter(today) }.minByOrNull { it.startDate }
            ?: terms.maxByOrNull { it.endDate }
            ?: return null
        val dates = Timetable.dates(entry.copy(inHolidays = false), terms, term.startDate, term.endDate)
        if (dates.isEmpty()) return "No dates in ${term.name}."
        val shown = dates.take(MAX_PREVIEW).joinToString(", ") { PlannerText.date(it, today).substringAfter(' ') }
        val more = if (dates.size > MAX_PREVIEW) ", …" else ""
        return "${dates.size} ${if (dates.size == 1) "date" else "dates"} in ${term.name}: $shown$more"
    }

    companion object {
        const val MAX_PREVIEW = 6

        fun from(entry: TimetableEntry) = EntryForm(
            id = entry.id,
            title = entry.title,
            kind = entry.kind,
            moduleId = entry.moduleId,
            location = entry.location,
            room = entry.room,
            contact = entry.contact,
            colorArgb = entry.colorArgb,
            note = entry.note,
            weekday = entry.weekday,
            start = entry.start,
            end = entry.end,
            pattern = when (val p = entry.pattern) {
                WeekPattern.EveryWeek -> PatternChoice.EVERY_WEEK
                is WeekPattern.TeachingWeeks -> PatternChoice.CHOSEN_WEEKS
                WeekPattern.OddWeeks -> PatternChoice.ODD_WEEKS
                WeekPattern.EvenWeeks -> PatternChoice.EVEN_WEEKS
                is WeekPattern.Once -> PatternChoice.ONCE
            },
            weeksText = (entry.pattern as? WeekPattern.TeachingWeeks)?.let { WeekList.format(it.weeks) } ?: "",
            onceDate = (entry.pattern as? WeekPattern.Once)?.date,
            termId = entry.termId,
            inHolidays = entry.inHolidays,
            archived = entry.archived,
            createdAt = entry.createdAt,
        )
    }
}
