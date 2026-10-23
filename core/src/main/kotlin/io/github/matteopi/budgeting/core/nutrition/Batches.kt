package io.github.matteopi.budgeting.core.nutrition

import java.time.LocalDate

/** Where a batch's portions are. */
enum class Place { FRIDGE, FREEZER }

/** How much an action took from the fridge and from the freezer, so it can be put back exactly. */
data class Taken(val fromFridge: Double, val fromFreezer: Double) {
    val total: Double get() = fromFridge + fromFreezer
}

/** A batch after an action, and what the action took. */
data class BatchChange(val batch: Batch, val taken: Taken)

/** What the kitchen shows about a batch, most pressing first. */
enum class BatchStatus(val label: String) {
    PAST_EAT_BY("Past its eat-by"),
    EAT_TODAY("Eat today"),
    EAT_TOMORROW("Eat by tomorrow"),
    IN_FRIDGE("In the fridge"),
    FROZEN_LONG("Frozen a while"),
    FROZEN("Frozen"),
    FINISHED("Finished"),
}

/** The kitchen's batches: making, eating, moving and throwing away portions (docs/PLAN.md §14.4). */
object Batches {

    /** Amounts closer than this are the same. */
    const val EPSILON = 1e-6

    /** A finished batch is listed under *Finished* for this many days after it was made. */
    const val FINISHED_DAYS = 14L

    /**
     * A new batch of [name]: [portions] made from [servings] of a recipe, [freezer] of them frozen and the rest in the
     * fridge. [perPortion] is one portion's intake and cost. The fridge portions keep for [FoodPrefs.fridgeDays].
     */
    fun make(
        name: String,
        recipeId: Long?,
        madeOn: LocalDate,
        servings: Double,
        portions: Double,
        freezer: Double,
        perPortion: Eaten,
        prefs: FoodPrefs = FoodPrefs(),
        nowMillis: Long = 0,
    ): Batch {
        val total = portions.coerceAtLeast(0.0)
        val frozen = freezer.coerceIn(0.0, total)
        return Batch(
            recipeId = recipeId,
            name = name,
            madeOn = madeOn,
            servings = servings,
            portions = total,
            fridgeLeft = total - frozen,
            freezerLeft = frozen,
            frozenOn = if (frozen > EPSILON) madeOn else null,
            eatBy = madeOn.plusDays(prefs.fridgeDays.toLong()),
            costPence = perPortion.costPence,
            createdAtEpochMillis = nowMillis,
            intake = perPortion.intake,
        )
    }

    /**
     * Takes [amount] portions off [batch]: from the fridge first, then the freezer, unless [from] says the freezer, in
     * which case the freezer goes first. Never below zero: it takes at most what is left.
     */
    fun take(batch: Batch, amount: Double, from: Place = Place.FRIDGE): BatchChange {
        val wanted = amount.coerceAtLeast(0.0)
        val (first, second) = if (from == Place.FRIDGE) batch.fridgeLeft to batch.freezerLeft else batch.freezerLeft to batch.fridgeLeft
        val fromFirst = minOf(wanted, first)
        val fromSecond = minOf(wanted - fromFirst, second)
        val taken = if (from == Place.FRIDGE) Taken(fromFirst, fromSecond) else Taken(fromSecond, fromFirst)
        return BatchChange(
            batch.copy(fridgeLeft = clean(batch.fridgeLeft - taken.fromFridge), freezerLeft = clean(batch.freezerLeft - taken.fromFreezer)),
            taken,
        )
    }

    /** Eating is taking: see [take]. */
    fun eat(batch: Batch, amount: Double, from: Place = Place.FRIDGE): BatchChange = take(batch, amount, from)

    /** Throws away [amount] portions (see [take]) and counts them as wasted. */
    fun throwAway(batch: Batch, amount: Double, from: Place = Place.FRIDGE): BatchChange {
        val change = take(batch, amount, from)
        return change.copy(batch = change.batch.copy(wasted = batch.wasted + change.taken.total))
    }

    /** Puts back what an action [taken] took (Undo), and takes it off [wasted] when it was thrown away. */
    fun putBack(batch: Batch, taken: Taken, wasThrownAway: Boolean = false): Batch = batch.copy(
        fridgeLeft = batch.fridgeLeft + taken.fromFridge,
        freezerLeft = batch.freezerLeft + taken.fromFreezer,
        wasted = if (wasThrownAway) clean((batch.wasted - taken.total).coerceAtLeast(0.0)) else batch.wasted,
    )

    /**
     * Where a deleted diary row's [amount] goes back: the fridge, unless the batch has none left there and some in
     * the freezer (it was taken from the freezer).
     */
    fun returnPlace(batch: Batch): Place =
        if (batch.fridgeLeft < EPSILON && batch.freezerLeft > EPSILON) Place.FREEZER else Place.FRIDGE

    /** Puts [amount] portions back into [place] (a deleted diary row). */
    fun putBack(batch: Batch, amount: Double, place: Place): Batch =
        putBack(batch, if (place == Place.FRIDGE) Taken(amount, 0.0) else Taken(0.0, amount))

    /**
     * Moves [amount] portions to [to]. Into the freezer: [Batch.frozenOn] becomes [today] if nothing was frozen.
     * Into the fridge (defrosting): they keep a day, so the eat-by becomes tomorrow unless the fridge's is sooner.
     */
    fun move(batch: Batch, amount: Double, to: Place, today: LocalDate): Batch {
        return if (to == Place.FREEZER) {
            val moved = minOf(amount.coerceAtLeast(0.0), batch.fridgeLeft)
            batch.copy(
                fridgeLeft = clean(batch.fridgeLeft - moved),
                freezerLeft = batch.freezerLeft + moved,
                frozenOn = if (batch.freezerLeft > EPSILON) batch.frozenOn ?: today else today,
            )
        } else {
            val moved = minOf(amount.coerceAtLeast(0.0), batch.freezerLeft)
            val defrosted = today.plusDays(1)
            val eatBy = if (batch.fridgeLeft > EPSILON) minOf(batch.eatBy ?: defrosted, defrosted) else defrosted
            val freezerLeft = clean(batch.freezerLeft - moved)
            batch.copy(
                fridgeLeft = batch.fridgeLeft + moved,
                freezerLeft = freezerLeft,
                eatBy = eatBy,
                frozenOn = if (freezerLeft > EPSILON) batch.frozenOn else null,
            )
        }
    }

    /** The day the frozen portions are best eaten by: frozen on + [FoodPrefs.freezerMonths]. */
    fun freezerBestBy(batch: Batch, prefs: FoodPrefs = FoodPrefs()): LocalDate? =
        batch.frozenOn?.plusMonths(prefs.freezerMonths.toLong())

    /** The batch's status on [today]: the fridge portions' eat-by first, then the freezer's. */
    fun status(batch: Batch, today: LocalDate, prefs: FoodPrefs = FoodPrefs()): BatchStatus {
        if (batch.finished) return BatchStatus.FINISHED
        if (batch.fridgeLeft > EPSILON) {
            val eatBy = batch.eatBy ?: return BatchStatus.IN_FRIDGE
            return when {
                today.isAfter(eatBy) -> BatchStatus.PAST_EAT_BY
                today == eatBy -> BatchStatus.EAT_TODAY
                today.plusDays(1) == eatBy -> BatchStatus.EAT_TOMORROW
                else -> BatchStatus.IN_FRIDGE
            }
        }
        val bestBy = freezerBestBy(batch, prefs)
        return if (bestBy != null && today.isAfter(bestBy)) BatchStatus.FROZEN_LONG else BatchStatus.FROZEN
    }

    /**
     * The batches the kitchen and the widget offer, most pressing first: the fridge's by eat-by (soonest first),
     * then the frozen ones by when they were frozen. Finished batches are left out.
     */
    fun ready(batches: List<Batch>): List<Batch> = batches.filter { !it.finished }.sortedWith(
        compareBy<Batch> { if (it.fridgeLeft > EPSILON) 0 else 1 }
            .thenBy { if (it.fridgeLeft > EPSILON) it.eatBy ?: LocalDate.MAX else it.frozenOn ?: LocalDate.MAX }
            .thenBy { it.madeOn }
            .thenBy { it.id },
    )

    /** Finished batches made in the last [FINISHED_DAYS] days, newest first. */
    fun recentlyFinished(batches: List<Batch>, today: LocalDate): List<Batch> =
        batches.filter { it.finished && !it.madeOn.isBefore(today.minusDays(FINISHED_DAYS)) }
            .sortedWith(compareByDescending<Batch> { it.madeOn }.thenByDescending { it.id })

    /** The cost of the portions thrown away from batches made in [from]..[to], in pence (priced batches only). */
    fun wastedPence(batches: List<Batch>, from: LocalDate, to: LocalDate): Double =
        batches.filter { !it.madeOn.isBefore(from) && !it.madeOn.isAfter(to) }.sumOf { (it.costPence ?: 0.0) * it.wasted }

    private fun clean(value: Double): Double = if (value < EPSILON) 0.0 else value
}
