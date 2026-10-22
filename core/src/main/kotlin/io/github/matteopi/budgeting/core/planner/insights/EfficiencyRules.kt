package io.github.matteopi.budgeting.core.planner.insights

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.Intervals
import io.github.matteopi.budgeting.core.planner.day.PlanOutcome
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.Occurrence
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Which efficiency tip, in the order they are listed. */
enum class TipKind { BEST_HOURS, SHORTER_BLOCKS, CLASS_GAPS, COMMUTE, CHORES, PHONE }

/** A tip: what to try, and [numbers], the figures behind it. */
data class Tip(val kind: TipKind, val text: String, val numbers: String)

/**
 * What the tips look at: the record, the plans and the classes of [period] (the app uses the last four weeks), with
 * the categories, which the tips find by their system keys.
 */
data class TipInput(
    val period: Period,
    val records: List<RecordChunk>,
    val plans: List<PlanBlock>,
    val occurrences: List<Occurrence>,
    val categories: List<TimeCategory>,
    val now: Instant,
    val zone: ZoneId,
) {
    fun idsOf(vararg keys: CategoryKey): Set<Long> = categories.filter { it.systemKey in keys }.map { it.id }.toSet()

    val window: Interval get() = period.interval(zone)

    /** Records of [ids] starting in the period. */
    fun recordsOf(ids: Set<Long>): List<RecordChunk> = records.filter { it.categoryId in ids && it.start in window }

    val weeks: Double get() = period.days / 7.0
}

/**
 * The efficiency tips (docs/PLAN.md §12.10, §13.4): one function per tip, each returning its [Tip] or null. Each is
 * rule-based and quiet unless its numbers clearly hold.
 */
object EfficiencyRules {
    /** Focus this far above (or below) the overall mean marks a good (or poor) time of day. */
    const val FOCUS_MARGIN = 0.7
    const val MIN_BUCKET_SAMPLES = 5
    const val MIN_LENGTH_SAMPLES = 3

    fun all(input: TipInput): List<Tip> =
        listOfNotNull(bestHours(input), shorterBlocks(input), classGaps(input), commute(input), chores(input), phone(input))

    /**
     * 1. **Best hours**: a 2-hour time of day rated [FOCUS_MARGIN] above the overall mean (with [MIN_BUCKET_SAMPLES]
     * ratings), while at least 30 % of the study minutes are in times rated [FOCUS_MARGIN] below it.
     */
    fun bestHours(input: TipInput): Tip? {
        val study = input.recordsOf(input.idsOf(CategoryKey.STUDY))
        val rated = study.filter { it.focus != null }
        if (rated.isEmpty()) return null
        val overall = rated.mapNotNull { it.focus }.average()
        val means = rated.groupBy { bucketOf(it, input.zone) }.mapValues { (_, list) -> list.mapNotNull { it.focus }.average() to list.size }
        val best = means.filter { (_, v) -> v.second >= MIN_BUCKET_SAMPLES && v.first >= overall + FOCUS_MARGIN }
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Pair<Double, Int>>> { it.value.first }.thenBy { it.key }).firstOrNull() ?: return null
        val poor = means.filter { (_, v) -> v.first <= overall - FOCUS_MARGIN }.keys
        if (poor.isEmpty()) return null
        val total = study.sumOf { minutesOf(it) }
        val inPoor = study.filter { bucketOf(it, input.zone) in poor }.sumOf { minutesOf(it) }
        if (total == 0L || inPoor < total * 0.3) return null
        val poorMean = rated.filter { bucketOf(it, input.zone) in poor }.mapNotNull { it.focus }.average()
        return Tip(
            TipKind.BEST_HOURS,
            "You focus best ${hours(listOf(best.key))}. ${percent(inPoor.toDouble() / total)} of your study is at ${hours(poor.toList())}, " +
                "rated ${one(poorMean)} on average: try moving some of it to your best hours.",
            "${hours(listOf(best.key))}: ${one(best.value.first)} over ${best.value.second} ratings · overall ${one(overall)} · " +
                "${PlannerText.duration(inPoor.toInt())} of ${PlannerText.duration(total.toInt())} at ${one(poorMean)}",
        )
    }

    /**
     * 2. **Shorter blocks**: chunks over 75 minutes rated [FOCUS_MARGIN] below those of 45 minutes or less, or planned
     * blocks over 75 minutes skipped at least 40 % of the time (each with [MIN_LENGTH_SAMPLES]).
     */
    fun shorterBlocks(input: TipInput): Tip? {
        val studyIds = input.idsOf(CategoryKey.STUDY)
        val rated = input.recordsOf(studyIds).filter { it.focus != null }
        val long = rated.filter { BlockLength.of(minutesOf(it)) == BlockLength.LONG }
        val short = rated.filter { BlockLength.of(minutesOf(it)) == BlockLength.SHORT }
        if (long.size >= MIN_LENGTH_SAMPLES && short.size >= MIN_LENGTH_SAMPLES) {
            val longMean = long.mapNotNull { it.focus }.average()
            val shortMean = short.mapNotNull { it.focus }.average()
            if (longMean <= shortMean - FOCUS_MARGIN) {
                return Tip(
                    TipKind.SHORTER_BLOCKS,
                    "Your study blocks over 75 minutes are rated ${one(longMean)}, against ${one(shortMean)} for 45 minutes or less: " +
                        "try shorter blocks with a break between them.",
                    "Over 75 m: ${one(longMean)} over ${long.size} ratings · 45 m or less: ${one(shortMean)} over ${short.size}",
                )
            }
        }
        val window = input.window
        val longPlans = input.plans.filter { it.categoryId in studyIds && it.start in window && BlockLength.of(Interval(it.start, it.end).minutes) == BlockLength.LONG }
            .mapNotNull { PlanOutcome.of(it, input.records, input.now) }
        val skipped = longPlans.count { it == Outcome.SKIPPED }
        if (longPlans.size >= MIN_LENGTH_SAMPLES && skipped >= longPlans.size * 0.4) {
            return Tip(
                TipKind.SHORTER_BLOCKS,
                "You skip ${percent(skipped.toDouble() / longPlans.size)} of the study blocks you plan over 75 minutes: " +
                    "shorter blocks are easier to start.",
                "Over 75 m: $skipped of ${longPlans.size} skipped",
            )
        }
        return null
    }

    /**
     * 3. **Gaps between classes**: a gap of 45 to 120 minutes between two classes, on the same weekday at the same
     * times for 3 weeks or more, mostly recorded as phone or rest. The gap with the most such time is named.
     */
    fun classGaps(input: TipInput): Tip? {
        data class GapKey(val weekday: java.time.DayOfWeek, val start: LocalTime, val end: LocalTime)
        val gaps = HashMap<GapKey, MutableList<Interval>>()
        input.occurrences.filter { !it.cancelled && it.date in input.period }.groupBy { it.date }.forEach { (_, day) ->
            val sorted = day.sortedBy { it.start }
            for (i in 0 until sorted.size - 1) {
                val gap = Interval(sorted[i].end, sorted[i + 1].start)
                if (gap.minutes !in 45L..120L) continue
                // A later class may end after the next one starts (overlaps); then there is no gap.
                if (sorted.drop(i + 1).any { it.start.isBefore(gap.end) && it.end.isAfter(gap.start) }) continue
                gaps.getOrPut(GapKey(sorted[i].date.dayOfWeek, sorted[i].endTime, sorted[i + 1].startTime)) { ArrayList() } += gap
            }
        }
        val idle = input.records.filter { it.categoryId in input.idsOf(CategoryKey.PHONE, CategoryKey.REST) }.map { Interval(it.start, it.end) }
        val found = gaps.mapNotNull { (key, list) ->
            val weeks = list.map { HabitProgress.weekStart(it.start.atZone(input.zone).toLocalDate()) }.distinct().size
            if (weeks < 3) return@mapNotNull null
            val total = list.sumOf { it.minutes }
            val lost = list.sumOf { Intervals.overlapMinutes(it, idle) }
            if (lost * 2 <= total) null else Triple(key, weeks, lost to total)
        }.sortedWith(compareByDescending<Triple<GapKey, Int, Pair<Long, Long>>> { it.third.first }.thenBy { it.first.weekday }.thenBy { it.first.start })
            .firstOrNull() ?: return null
        val (key, weeks, minutes) = found
        val day = key.weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return Tip(
            TipKind.CLASS_GAPS,
            "Your $day gap between classes, ${PlannerText.range(key.start, key.end)}, has mostly gone on phone and rest: " +
                "it is long enough for a study block.",
            "$weeks weeks · ${PlannerText.duration(minutes.first.toInt())} of ${PlannerText.duration(minutes.second.toInt())} on phone or rest",
        )
    }

    /** 4. **Commute revision**: 3 hours or more of travel a week on average. */
    fun commute(input: TipInput): Tip? {
        val minutes = input.recordsOf(input.idsOf(CategoryKey.TRAVEL)).sumOf { minutesOf(it) }
        val perWeek = (minutes / input.weeks).roundToInt()
        if (perWeek < 180) return null
        return Tip(
            TipKind.COMMUTE,
            "You travel ${PlannerText.duration(perWeek)} a week: flashcards, reading or a lecture recording could make some of it revision.",
            "${PlannerText.duration(minutes.toInt())} of travel over ${weeksText(input)}",
        )
    }

    /** 5. **Batching chores**: 5 or more chore chunks a week, averaging under 20 minutes. */
    fun chores(input: TipInput): Tip? {
        val chunks = input.recordsOf(input.idsOf(CategoryKey.CHORES))
        if (chunks.isEmpty()) return null
        val perWeek = chunks.size / input.weeks
        val average = chunks.sumOf { minutesOf(it) }.toDouble() / chunks.size
        if (perWeek < 5 || average >= 20) return null
        return Tip(
            TipKind.CHORES,
            "Your chores come in about ${perWeek.roundToInt()} short chunks a week, ${average.roundToInt()} minutes each on average: " +
                "doing them together in one or two slots saves switching.",
            "${chunks.size} chunks over ${weeksText(input)} · ${average.roundToInt()} m each",
        )
    }

    /** 6. **Phone time**: in the period's last week, 1.5 hours a day or more on average, or up 25 % or more on the week before. */
    fun phone(input: TipInput): Tip? {
        val ids = input.idsOf(CategoryKey.PHONE)
        if (ids.isEmpty()) return null
        val last = Period(maxOf(input.period.from, input.period.to.minusDays(6)), input.period.to)
        val before = last.previous()
        fun minutesIn(p: Period): Long {
            val window = p.interval(input.zone)
            return input.records.filter { it.categoryId in ids }.sumOf { Interval(it.start, it.end).intersect(window)?.minutes ?: 0L }
        }
        val now = minutesIn(last)
        val then = minutesIn(before)
        val perDay = (now / last.days.toDouble()).roundToInt()
        val up = then > 0 && now >= 60 && now >= then * 1.25
        if (perDay < 90 && !up) return null
        val change = if (then > 0) " · the week before: ${PlannerText.duration(then.toInt())}" else ""
        val text = if (up) {
            "Phone and scrolling took ${PlannerText.duration(now.toInt())} in the last week, up ${percent((now - then).toDouble() / then)} on the week before."
        } else {
            "Phone and scrolling took ${PlannerText.duration(perDay)} a day in the last week."
        }
        return Tip(TipKind.PHONE, text, "${PlannerText.duration(now.toInt())} in ${last.days} days · ${PlannerText.duration(perDay)} a day$change")
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun bucketOf(record: RecordChunk, zone: ZoneId): Int = record.start.atZone(zone).hour / 2 * 2

    private fun minutesOf(record: RecordChunk): Long = Interval(record.start, record.end).minutes

    /** 2-hour times of day as ranges, neighbours joined: "09:00–11:00", "08:00–10:00 and 18:00–22:00". */
    private fun hours(buckets: List<Int>): String {
        val runs = ArrayList<IntRange>()
        for (h in buckets.sorted()) {
            val lastRun = runs.lastOrNull()
            if (lastRun != null && lastRun.last + 2 == h) runs[runs.size - 1] = lastRun.first..h else runs += h..h
        }
        val texts = runs.map { r ->
            val end = if (r.last + 2 >= 24) LocalTime.MIDNIGHT else LocalTime.of(r.last + 2, 0)
            PlannerText.range(LocalTime.of(r.first, 0), end)
        }
        return when (texts.size) {
            1 -> texts.single()
            else -> texts.dropLast(1).joinToString(", ") + " and " + texts.last()
        }
    }

    private fun one(value: Double): String = (Math.round(value * 10) / 10.0).toString()

    private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"

    private fun weeksText(input: TipInput): String = if (input.period.days % 7 == 0) "${input.period.days / 7} weeks" else "${input.period.days} days"
}
