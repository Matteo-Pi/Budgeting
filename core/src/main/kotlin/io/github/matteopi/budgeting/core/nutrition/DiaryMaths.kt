package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.RecipeMaths

/** Where a nutrient is against its target. */
enum class TargetState(val label: String) { UNDER("Under"), ON_TRACK("On track"), OVER("Over") }

/** One nutrient's amount against its target or reference. [atLeast] when part of it is unknown. */
data class TargetProgress(
    val key: IntakeKey,
    val amount: Double,
    val target: Double,
    val kind: TargetKind,
    val state: TargetState,
    val atLeast: Boolean,
) {
    /** The amount as a fraction of the target (1.0 = all of it). */
    val fraction: Double get() = if (target > 0) amount / target else 0.0

    /** How much is left to the target, never below zero. */
    val left: Double get() = (target - amount).coerceAtLeast(0.0)
}

/** A day of the diary added up (docs/PLAN.md §14.4). */
data class DaySummary(
    val total: Intake,
    val byMeal: Map<MealSlot, Intake>,
    /** 5 a day portions: Σ veg ÷ 80 + min(1, Σ pulses ÷ 80). */
    val vegPortions: Double,
    /** The known part of the day's cost, in pence. */
    val costPence: Double,
    /** Rows with no price: when there are any, the cost is "at least". */
    val unpriced: Int,
    val waterMl: Int,
    val progress: Map<IntakeKey, TargetProgress>,
    val items: Int,
) {
    val kcal: Double get() = total.kcal
    fun of(key: IntakeKey): TargetProgress = progress.getValue(key)
}

object DiaryMaths {

    /** An aimed-at target is on track between these fractions of it. */
    const val AIM_LOW = 0.9
    const val AIM_HIGH = 1.1

    /**
     * The day's totals overall and per meal, its veg (the pulses cap applies to the day), its cost, and every
     * nutrient against [targets]: an aim is under below 90%, over above 110%; "at least" is under below 90% and
     * never over; a limit is over past 100%.
     */
    fun day(items: List<LogItem>, waterMl: Int = 0, targets: FoodTargets = FoodTargets()): DaySummary {
        val total = Intake.sum(items.map { it.intake })
        val byMeal = MealSlot.entries.associateWith { meal -> Intake.sum(items.filter { it.meal == meal }.map { it.intake }) }
        return DaySummary(
            total = total,
            byMeal = byMeal,
            vegPortions = vegPortions(total),
            costPence = items.sumOf { it.costPence ?: 0.0 },
            unpriced = items.count { it.costPence == null && it.kind != LogKind.QUICK },
            waterMl = waterMl,
            progress = IntakeKey.entries.associateWith { progress(it, total, targets) },
            items = items.size,
        )
    }

    /** 5 a day portions in [intake], pulses counted once at most. */
    fun vegPortions(intake: Intake): Double =
        intake.vegGrams / RecipeMaths.VEG_PORTION_GRAMS + minOf(1.0, intake.pulseGrams / RecipeMaths.VEG_PORTION_GRAMS)

    fun progress(key: IntakeKey, total: Intake, targets: FoodTargets): TargetProgress {
        val amount = total[key]
        val target = targets[key]
        val kind = FoodTargets.goalOf(key)
        val fraction = if (target > 0) amount / target else 0.0
        val state = when (kind) {
            TargetKind.AIM -> when {
                fraction < AIM_LOW -> TargetState.UNDER
                fraction > AIM_HIGH -> TargetState.OVER
                else -> TargetState.ON_TRACK
            }
            TargetKind.AT_LEAST -> if (fraction < AIM_LOW) TargetState.UNDER else TargetState.ON_TRACK
            TargetKind.LIMIT -> if (fraction > 1.0) TargetState.OVER else TargetState.ON_TRACK
        }
        return TargetProgress(key, amount, target, kind, state, key in total.unknown)
    }

    /**
     * The rows that gave the most of [key], largest first, with their share of the day's total: what *All
     * nutrients* names under each nutrient.
     */
    fun topSources(items: List<LogItem>, key: IntakeKey, limit: Int = 3): List<Pair<LogItem, Double>> {
        val total = items.sumOf { it.intake[key] }
        if (total <= 0) return emptyList()
        return items.filter { it.intake[key] > 0 }.sortedByDescending { it.intake[key] }.take(limit).map { it to it.intake[key] / total }
    }

    /** The rows in diary order: by meal, then each portion followed by its sides, then by time eaten. */
    fun ordered(items: List<LogItem>): List<LogItem> {
        val sides = items.filter { it.sideOf != null }.groupBy { it.sideOf }
        val ids = items.map { it.id }.toSet()
        val mains = items.filter { it.sideOf == null || it.sideOf !in ids }
            .sortedWith(compareBy<LogItem> { it.meal.ordinal }.thenBy { it.eatenAtEpochMillis }.thenBy { it.id })
        return mains.flatMap { main -> listOf(main) + sides[main.id].orEmpty().sortedBy { it.id } }
    }
}
