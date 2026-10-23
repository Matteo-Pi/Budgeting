package io.github.matteopi.budgeting.core.nutrition

import java.time.LocalDate

/** One day of a trend: its kcal and whether anything was logged. */
data class TrendDay(val date: LocalDate, val kcal: Double, val logged: Boolean, val waterMl: Int, val costPence: Double, val vegPortions: Double)

/** A food's share of a nutrient or of the cost over the days: its name, the amount and its fraction of the total. */
data class Source(val name: String, val amount: Double, val fraction: Double)

/** The days [from]..[to] of the diary, averaged per logged day (docs/PLAN.md §14.4). */
data class Insights(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<TrendDay>,
    val loggedDays: Int,
    /** Everything per logged day. */
    val average: Intake,
    val progress: Map<IntakeKey, TargetProgress>,
    /** Vitamins and minerals under half their reference on average. */
    val lowMicros: List<Micro>,
    val topKcal: List<Source>,
    val topProtein: List<Source>,
    val topCost: List<Source>,
    val costPerDay: Double,
    val vegPerDay: Double,
    val waterPerDay: Double,
    /** Days on which kcal were within 10% of the target. */
    val daysOnTarget: Int,
    val wastedPortions: Double,
    val wastedPence: Double,
)

object NutritionInsights {

    /** A micro under this fraction of its reference on average is flagged. */
    const val LOW = 0.5

    /**
     * The insights for [from]..[to] from [items] (rows outside are left out), [days]' water, and the [batches] made
     * in that time for the waste.
     */
    fun of(
        items: List<LogItem>,
        from: LocalDate,
        to: LocalDate,
        targets: FoodTargets = FoodTargets(),
        days: List<FoodDay> = emptyList(),
        batches: List<Batch> = emptyList(),
    ): Insights {
        val inRange = items.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val byDate = inRange.groupBy { it.date }
        val water = days.associate { it.date to it.waterMl }
        val trend = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { date ->
            val rows = byDate[date].orEmpty()
            val total = Intake.sum(rows.map { it.intake })
            TrendDay(date, total.kcal, rows.isNotEmpty(), water[date] ?: 0, rows.sumOf { it.costPence ?: 0.0 }, DiaryMaths.vegPortions(total))
        }.toList()
        val logged = trend.count { it.logged }
        val divisor = logged.coerceAtLeast(1).toDouble()
        val average = Intake.sum(inRange.map { it.intake }) / divisor
        val progress = IntakeKey.entries.associateWith { DiaryMaths.progress(it, average, targets) }
        val lowMicros = if (logged == 0) emptyList() else Micro.entries.filter { average[it] < it.reference * LOW }
        val waterDays = trend.filter { it.logged || it.waterMl > 0 }

        val made = batches.filter { !it.madeOn.isBefore(from) && !it.madeOn.isAfter(to) }
        return Insights(
            from = from,
            to = to,
            days = trend,
            loggedDays = logged,
            average = average,
            progress = progress,
            lowMicros = lowMicros,
            topKcal = sources(inRange) { it.intake.kcal },
            topProtein = sources(inRange) { it.intake.protein },
            topCost = sources(inRange) { it.costPence ?: 0.0 },
            costPerDay = trend.filter { it.logged }.sumOf { it.costPence } / divisor,
            vegPerDay = trend.filter { it.logged }.sumOf { it.vegPortions } / divisor,
            waterPerDay = if (waterDays.isEmpty()) 0.0 else waterDays.sumOf { it.waterMl }.toDouble() / waterDays.size,
            daysOnTarget = trend.count { it.logged && targets.kcal > 0 && Math.abs(it.kcal / targets.kcal - 1) <= 0.1 },
            wastedPortions = made.sumOf { it.wasted },
            wastedPence = Batches.wastedPence(made, from, to),
        )
    }

    /** The five names that gave the most of [value], largest first. */
    private fun sources(items: List<LogItem>, value: (LogItem) -> Double): List<Source> {
        val byName = items.groupBy { it.name }.mapValues { (_, rows) -> rows.sumOf(value) }.filterValues { it > 0 }
        val total = byName.values.sum()
        return byName.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(5).map { Source(it.key, it.value, it.value / total) }
    }
}
