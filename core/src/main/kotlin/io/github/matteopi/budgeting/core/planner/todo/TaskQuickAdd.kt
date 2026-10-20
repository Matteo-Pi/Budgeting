package io.github.matteopi.budgeting.core.planner.todo

import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What a span of the quick-add text was read as, for highlighting it in the preview. */
enum class QuickAddPart { DATE, TIME, ESTIMATE, MODULE, TAG, PRIORITY }

/** [start] inclusive, [end] exclusive, in the original text. */
data class QuickAddSpan(val start: Int, val end: Int, val part: QuickAddPart)

/** A task read from one line. [title] may be blank, which the editor then asks for. */
data class QuickAdd(
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val estimateMinutes: Int? = null,
    val moduleId: Long? = null,
    val tags: List<String> = emptyList(),
    val priority: Priority = Priority.NONE,
    val spans: List<QuickAddSpan> = emptyList(),
)

/**
 * Reads a one-line task such as "Econ essay draft fri 3pm 2h #EC101" (docs/PLAN.md §13.4).
 *
 * Words are matched case-insensitively and in any order; the first date, time and estimate count and later
 * ones stay in the title. "#CODE" is the module with that code, else a tag; "!", "!!" and "!!!" are low, medium
 * and high priority. A connector ("for", "at", "on", "by", "due") just before a word that was read is dropped,
 * and text in quotes is always title. A time without a date is today's. "sat" and "sun" are days only at the end
 * or before another word that is read ("Buy sun cream" stays as it is). It never throws.
 */
object TaskQuickAdd {

    private const val MAX_ESTIMATE_MINUTES = 200 * 60

    private val CONNECTORS = setOf("for", "at", "on", "by", "due")

    private val AMBIGUOUS_DAYS = setOf("sat", "sun")

    private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        for (day in DayOfWeek.entries) {
            val name = day.name.lowercase(Locale.ENGLISH)
            put(name, day)
            put(name.take(3), day)
        }
        put("tues", DayOfWeek.TUESDAY)
        put("weds", DayOfWeek.WEDNESDAY)
        put("thur", DayOfWeek.THURSDAY)
        put("thurs", DayOfWeek.THURSDAY)
    }

    private val MONTHS: Map<String, Int> = buildMap {
        listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        ).forEachIndexed { index, name ->
            put(name, index + 1)
            put(name.take(3), index + 1)
        }
        put("sept", 9)
    }

    private val DAY_NUMBER = Regex("""^(\d{1,2})(st|nd|rd|th)?$""")
    private val SLASH_DATE = Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{2}|\d{4}))?$""")
    private val YEAR = Regex("""^\d{4}$""")
    private val TWELVE_HOUR = Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm)$""")
    private val TWENTY_FOUR_HOUR = Regex("""^(\d{1,2}):(\d{2})$""")
    private val CLOCK_NUMBER = Regex("""^(\d{1,2})(?::(\d{2}))?$""")
    private val HOURS = Regex("""^(\d+(?:\.\d+)?)(h|hr|hrs|hour|hours)$""")
    private val MINUTES = Regex("""^(\d+)(m|min|mins|minute|minutes)$""")
    private val HOURS_MINUTES = Regex("""^(\d+)h(\d{1,2})(m|min|mins)?$""")
    private val NUMBER = Regex("""^\d+(?:\.\d+)?$""")
    private val HOUR_UNITS = setOf("h", "hr", "hrs", "hour", "hours")
    private val MINUTE_UNITS = setOf("m", "min", "mins", "minute", "minutes")

    /** A word of the text: [text] as written, [key] lower case without surrounding punctuation. */
    private data class Word(val text: String, val start: Int, val end: Int, val quoted: Boolean) {
        val key: String = if (quoted) "" else text.lowercase(Locale.ENGLISH).trim(',', '.', ';', ':', '?', ')', '(')
    }

    private data class Match(val part: QuickAddPart, val words: Int, val value: Any)

    fun parse(text: String, today: LocalDate, modules: List<Module> = emptyList()): QuickAdd {
        val words = split(text)
        val taken = BooleanArray(words.size)
        val spans = mutableListOf<QuickAddSpan>()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var estimate: Int? = null
        var moduleId: Long? = null
        val tags = mutableListOf<String>()
        var priority = Priority.NONE

        var i = 0
        while (i < words.size) {
            val found = if (words[i].quoted) null else matchAt(words, i, today, modules)
            // A second module code is kept as a tag.
            val match = if (found?.part == QuickAddPart.MODULE && moduleId != null) {
                Match(QuickAddPart.TAG, 1, words[i].text.trim().trimEnd(',', '.', ';', ':').removePrefix("#"))
            } else {
                found
            }
            val accepted = match != null && when (match.part) {
                QuickAddPart.DATE -> (date == null).also { if (it) date = match.value as LocalDate }
                QuickAddPart.TIME -> (time == null).also { if (it) time = match.value as LocalTime }
                QuickAddPart.ESTIMATE -> (estimate == null).also { if (it) estimate = match.value as Int }
                QuickAddPart.MODULE -> (moduleId == null).also { if (it) moduleId = match.value as Long }
                QuickAddPart.TAG -> true.also { tags += match.value as String }
                QuickAddPart.PRIORITY -> (priority == Priority.NONE).also { if (it) priority = match.value as Priority }
            }
            if (match != null && accepted) {
                for (k in i until i + match.words) taken[k] = true
                spans += QuickAddSpan(words[i].start, words[i + match.words - 1].end, match.part)
                // A connector just before what was read ("for 45min", "by fri") goes with it.
                if (i > 0 && !taken[i - 1] && !words[i - 1].quoted && words[i - 1].key in CONNECTORS) taken[i - 1] = true
                i += match.words
            } else {
                i++
            }
        }

        val title = words.filterIndexed { index, _ -> !taken[index] }
            .joinToString(" ") { if (it.quoted) it.text else it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        return QuickAdd(
            title = title,
            dueDate = date ?: if (time != null) today else null,
            dueTime = time,
            estimateMinutes = estimate,
            moduleId = moduleId,
            tags = tags.distinct(),
            priority = priority,
            spans = spans.sortedBy { it.start },
        )
    }

    /** Words split on spaces, with each quoted part ("…" or “…”) as one word. */
    private fun split(text: String): List<Word> {
        val words = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '“' -> {
                    val close = if (c == '"') '"' else '”'
                    val end = text.indexOf(close, i + 1)
                    if (end < 0) {
                        // An unclosed quote: the rest of the text is title.
                        words += Word(text.substring(i + 1), i, text.length, quoted = true)
                        i = text.length
                    } else {
                        words += Word(text.substring(i + 1, end), i, end + 1, quoted = true)
                        i = end + 1
                    }
                }
                else -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] != '"' && text[end] != '“') end++
                    words += Word(text.substring(i, end), i, end, quoted = false)
                    i = end
                }
            }
        }
        return words
    }

    private fun matchAt(words: List<Word>, i: Int, today: LocalDate, modules: List<Module>): Match? {
        val key = words[i].key
        if (key.isEmpty()) return null
        fun next(offset: Int): String? = words.getOrNull(i + offset)?.takeIf { !it.quoted }?.key

        // Module, tag and priority marks.
        if (key.startsWith("#") && key.length > 1) {
            val code = words[i].text.trim().trimEnd(',', '.', ';', ':').removePrefix("#")
            if (code.isNotEmpty()) {
                val module = modules.firstOrNull { !it.archived && it.code.equals(code, ignoreCase = true) }
                return if (module != null) Match(QuickAddPart.MODULE, 1, module.id) else Match(QuickAddPart.TAG, 1, code)
            }
        }
        when (words[i].text.trim()) {
            "!" -> return Match(QuickAddPart.PRIORITY, 1, Priority.LOW)
            "!!" -> return Match(QuickAddPart.PRIORITY, 1, Priority.MEDIUM)
            "!!!" -> return Match(QuickAddPart.PRIORITY, 1, Priority.HIGH)
        }

        // Dates.
        when (key) {
            "today" -> return Match(QuickAddPart.DATE, 1, today)
            "tomorrow", "tmrw", "tmr" -> return Match(QuickAddPart.DATE, 1, today.plusDays(1))
        }
        WEEKDAYS[key]?.let { day ->
            // "sat" and "sun" are also words ("sun cream"): they are days only at the end or before something read.
            val ambiguous = key in AMBIGUOUS_DAYS &&
                words.getOrNull(i + 1)?.let { it.quoted || matchAt(words, i + 1, today, modules) == null } == true
            if (!ambiguous) return Match(QuickAddPart.DATE, 1, today.with(TemporalAdjusters.nextOrSame(day)))
        }
        SLASH_DATE.matchEntire(key)?.let { m ->
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            dayMonth(m.groupValues[1].toInt(), m.groupValues[2].toInt(), year, today)?.let { return Match(QuickAddPart.DATE, 1, it) }
        }
        // "23 oct", "23rd october 2026".
        DAY_NUMBER.matchEntire(key)?.let { m ->
            val month = next(1)?.let { MONTHS[it] }
            if (month != null) {
                val year = next(2)?.takeIf { YEAR.matches(it) }?.toInt()
                dayMonth(m.groupValues[1].toInt(), month, year, today)?.let {
                    return Match(QuickAddPart.DATE, if (year != null) 3 else 2, it)
                }
            }
        }
        // "oct 23", "october 23rd 2026".
        MONTHS[key]?.let { month ->
            val day = next(1)?.let { DAY_NUMBER.matchEntire(it) }?.groupValues?.get(1)?.toInt()
            if (day != null) {
                val year = next(2)?.takeIf { YEAR.matches(it) }?.toInt()
                dayMonth(day, month, year, today)?.let { return Match(QuickAddPart.DATE, if (year != null) 3 else 2, it) }
            }
        }

        // Times.
        if (key == "noon" || key == "midday") return Match(QuickAddPart.TIME, 1, LocalTime.NOON)
        TWELVE_HOUR.matchEntire(key)?.let { m ->
            twelveHour(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3])?.let { return Match(QuickAddPart.TIME, 1, it) }
        }
        // "3 pm", "3:30 pm" (before "3:30" alone, which is 24-hour).
        CLOCK_NUMBER.matchEntire(key)?.let { m ->
            val meridiem = next(1)
            if (meridiem == "am" || meridiem == "pm") {
                twelveHour(m.groupValues[1].toInt(), m.groupValues[2], meridiem)?.let { return Match(QuickAddPart.TIME, 2, it) }
            }
        }
        TWENTY_FOUR_HOUR.matchEntire(key)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) return Match(QuickAddPart.TIME, 1, LocalTime.of(hour, minute))
        }

        // Estimates.
        HOURS_MINUTES.matchEntire(key)?.let { m ->
            minutes(m.groupValues[1].toDouble() * 60 + m.groupValues[2].toInt())?.let { return Match(QuickAddPart.ESTIMATE, 1, it) }
        }
        HOURS.matchEntire(key)?.let { m -> minutes(m.groupValues[1].toDouble() * 60)?.let { return Match(QuickAddPart.ESTIMATE, 1, it) } }
        MINUTES.matchEntire(key)?.let { m -> minutes(m.groupValues[1].toDouble())?.let { return Match(QuickAddPart.ESTIMATE, 1, it) } }
        if (NUMBER.matches(key)) {
            val unit = next(1)
            val amount = key.toDouble()
            val value = when (unit) {
                in HOUR_UNITS -> minutes(amount * 60)
                in MINUTE_UNITS -> if (key.contains('.')) null else minutes(amount)
                else -> null
            }
            if (value != null) return Match(QuickAddPart.ESTIMATE, 2, value)
        }
        return null
    }

    /** The date [day]/[month] in [year], or (without a year) the next one on or after [today]; null if it doesn't exist. */
    private fun dayMonth(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? = try {
        if (year != null) {
            LocalDate.of(year, month, day)
        } else {
            val monthDay = MonthDay.of(month, day)
            val thisYear = if (monthDay.isValidYear(today.year)) monthDay.atYear(today.year) else null
            if (thisYear != null && !thisYear.isBefore(today)) {
                thisYear
            } else {
                // Next year, or the next leap year for 29 Feb.
                (1..8).map { today.year + it }.firstOrNull { monthDay.isValidYear(it) }?.let { monthDay.atYear(it) }
            }
        }
    } catch (e: DateTimeException) {
        null
    }

    private fun twelveHour(hour: Int, minuteText: String, meridiem: String): LocalTime? {
        val minute = if (minuteText.isEmpty()) 0 else minuteText.toInt()
        if (hour !in 1..12 || minute !in 0..59) return null
        val h24 = when {
            meridiem == "am" && hour == 12 -> 0
            meridiem == "am" -> hour
            hour == 12 -> 12
            else -> hour + 12
        }
        return LocalTime.of(h24, minute)
    }

    private fun minutes(value: Double): Int? {
        val rounded = Math.round(value).toInt()
        return rounded.takeIf { it in 1..MAX_ESTIMATE_MINUTES }
    }
}
