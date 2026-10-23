package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.ReferenceIntakes

/*
 * Vitamins and minerals, and everything the food diary adds up (docs/PLAN.md §14.2).
 */

/**
 * A vitamin or mineral with its unit and daily reference: the UK/EU label NRVs, except vitamin D, which uses the
 * NHS's 10 µg.
 */
enum class Micro(val label: String, val unit: String, val reference: Double) {
    VITAMIN_A("Vitamin A", "µg", 800.0),
    VITAMIN_C("Vitamin C", "mg", 80.0),
    VITAMIN_D("Vitamin D", "µg", 10.0),
    VITAMIN_E("Vitamin E", "mg", 12.0),
    VITAMIN_B6("Vitamin B6", "mg", 1.4),
    VITAMIN_B12("Vitamin B12", "µg", 2.5),
    FOLATE("Folate", "µg", 200.0),
    CALCIUM("Calcium", "mg", 800.0),
    IRON("Iron", "mg", 14.0),
    POTASSIUM("Potassium", "mg", 2000.0),
    MAGNESIUM("Magnesium", "mg", 375.0),
    ZINC("Zinc", "mg", 10.0),
    ;

    /** [amount] as a percentage of the reference. */
    fun percent(amount: Double): Double = amount / reference * 100

    /** An amount for display: "1.2 mg", "35 µg" (one decimal place under 10). */
    fun format(amount: Double): String = "${Quantities.plain(amount, if (amount < 10) 1 else 0)} $unit"
}

/** Vitamins and minerals per 100 g or 100 ml of an ingredient, in [Micro]'s units; null means unknown. */
data class Micros(
    val vitaminA: Double? = null,
    val vitaminC: Double? = null,
    val vitaminD: Double? = null,
    val vitaminE: Double? = null,
    val vitaminB6: Double? = null,
    val vitaminB12: Double? = null,
    val folate: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val potassium: Double? = null,
    val magnesium: Double? = null,
    val zinc: Double? = null,
) {
    operator fun get(micro: Micro): Double? = when (micro) {
        Micro.VITAMIN_A -> vitaminA
        Micro.VITAMIN_C -> vitaminC
        Micro.VITAMIN_D -> vitaminD
        Micro.VITAMIN_E -> vitaminE
        Micro.VITAMIN_B6 -> vitaminB6
        Micro.VITAMIN_B12 -> vitaminB12
        Micro.FOLATE -> folate
        Micro.CALCIUM -> calcium
        Micro.IRON -> iron
        Micro.POTASSIUM -> potassium
        Micro.MAGNESIUM -> magnesium
        Micro.ZINC -> zinc
    }

    /** True when every value is unknown. */
    val isEmpty: Boolean get() = Micro.entries.all { get(it) == null }

    /** A copy with [micro] set to [value]. */
    fun with(micro: Micro, value: Double?): Micros = when (micro) {
        Micro.VITAMIN_A -> copy(vitaminA = value)
        Micro.VITAMIN_C -> copy(vitaminC = value)
        Micro.VITAMIN_D -> copy(vitaminD = value)
        Micro.VITAMIN_E -> copy(vitaminE = value)
        Micro.VITAMIN_B6 -> copy(vitaminB6 = value)
        Micro.VITAMIN_B12 -> copy(vitaminB12 = value)
        Micro.FOLATE -> copy(folate = value)
        Micro.CALCIUM -> copy(calcium = value)
        Micro.IRON -> copy(iron = value)
        Micro.POTASSIUM -> copy(potassium = value)
        Micro.MAGNESIUM -> copy(magnesium = value)
        Micro.ZINC -> copy(zinc = value)
    }

    companion object {
        /** Micros from values in [Micro] order. */
        fun of(values: List<Double?>): Micros {
            require(values.size == Micro.entries.size) { "Micros need ${Micro.entries.size} values." }
            return Micro.entries.fold(Micros()) { micros, micro -> micros.with(micro, values[micro.ordinal]) }
        }
    }
}

/** Everything the diary adds up: the nine nutrients of a recipe, then the twelve vitamins and minerals. */
enum class IntakeKey(val nutrient: Nutrient?, val micro: Micro?) {
    KCAL(Nutrient.KCAL, null),
    FAT(Nutrient.FAT, null),
    SATURATES(Nutrient.SATURATES, null),
    CARBS(Nutrient.CARBS, null),
    SUGARS(Nutrient.SUGARS, null),
    ADDED_SUGARS(Nutrient.ADDED_SUGARS, null),
    FIBRE(Nutrient.FIBRE, null),
    PROTEIN(Nutrient.PROTEIN, null),
    SALT(Nutrient.SALT, null),
    VITAMIN_A(null, Micro.VITAMIN_A),
    VITAMIN_C(null, Micro.VITAMIN_C),
    VITAMIN_D(null, Micro.VITAMIN_D),
    VITAMIN_E(null, Micro.VITAMIN_E),
    VITAMIN_B6(null, Micro.VITAMIN_B6),
    VITAMIN_B12(null, Micro.VITAMIN_B12),
    FOLATE(null, Micro.FOLATE),
    CALCIUM(null, Micro.CALCIUM),
    IRON(null, Micro.IRON),
    POTASSIUM(null, Micro.POTASSIUM),
    MAGNESIUM(null, Micro.MAGNESIUM),
    ZINC(null, Micro.ZINC),
    ;

    val label: String
        get() = micro?.label ?: when (nutrient!!) {
            Nutrient.KCAL -> "Energy"
            Nutrient.FAT -> "Fat"
            Nutrient.SATURATES -> "Saturates"
            Nutrient.CARBS -> "Carbohydrate"
            Nutrient.SUGARS -> "Sugars"
            Nutrient.ADDED_SUGARS -> "Added sugars"
            Nutrient.FIBRE -> "Fibre"
            Nutrient.PROTEIN -> "Protein"
            Nutrient.SALT -> "Salt"
        }

    val unit: String get() = micro?.unit ?: if (this == KCAL) "kcal" else "g"

    /** The daily reference: the UK reference intakes (fibre and added sugars: their guidelines), or the NRV. */
    val reference: Double get() = micro?.reference ?: ReferenceIntakes.of(nutrient!!)

    /** An amount for display: "690 kcal", "23.4 g", "1.2 mg". */
    fun format(amount: Double): String = micro?.format(amount) ?: when (this) {
        KCAL -> "${Quantities.roundHalfUp(amount)} kcal"
        else -> "${Quantities.plain(amount, 1)} g"
    }

    companion object {
        fun of(nutrient: Nutrient): IntakeKey = entries.first { it.nutrient == nutrient }
        fun of(micro: Micro): IntakeKey = entries.first { it.micro == micro }
    }
}

/**
 * What was eaten (or is in one portion): an amount for every [IntakeKey] in its order (kcal, grams, then each
 * micro's unit), the veg and pulse grams for 5 a day, and the keys with an unknown part (an unknown value counted
 * as 0, so that amount is "at least").
 */
data class Intake(
    val amounts: List<Double> = ZEROS,
    val vegGrams: Double = 0.0,
    val pulseGrams: Double = 0.0,
    val unknown: Set<IntakeKey> = emptySet(),
) {
    init {
        require(amounts.size == IntakeKey.entries.size) { "An intake needs ${IntakeKey.entries.size} amounts." }
    }

    operator fun get(key: IntakeKey): Double = amounts[key.ordinal]
    operator fun get(nutrient: Nutrient): Double = get(IntakeKey.of(nutrient))
    operator fun get(micro: Micro): Double = get(IntakeKey.of(micro))

    val kcal: Double get() = get(IntakeKey.KCAL)
    val protein: Double get() = get(IntakeKey.PROTEIN)
    val carbs: Double get() = get(IntakeKey.CARBS)
    val fat: Double get() = get(IntakeKey.FAT)

    operator fun plus(other: Intake) = Intake(
        amounts.zip(other.amounts) { a, b -> a + b },
        vegGrams + other.vegGrams,
        pulseGrams + other.pulseGrams,
        unknown + other.unknown,
    )

    operator fun times(factor: Double) = Intake(amounts.map { it * factor }, vegGrams * factor, pulseGrams * factor, unknown)

    operator fun div(divisor: Double) = times(1.0 / divisor)

    /** The unknown keys as bits: bit n is the [IntakeKey] with ordinal n (the `unknown_mask` column). */
    val unknownMask: Int get() = unknown.fold(0) { mask, key -> mask or (1 shl key.ordinal) }

    /** True when every amount is unknown, as for an ingredient with no nutrition at all. */
    val allUnknown: Boolean get() = unknown.size == IntakeKey.entries.size

    companion object {
        val ZEROS: List<Double> = List(IntakeKey.entries.size) { 0.0 }
        val NONE = Intake()

        /** The keys a stored [mask] names. */
        fun unknownOf(mask: Int): Set<IntakeKey> = IntakeKey.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()

        /** An intake from amounts by key; keys left out are 0. */
        fun of(values: Map<IntakeKey, Double>, vegGrams: Double = 0.0, pulseGrams: Double = 0.0, unknown: Set<IntakeKey> = emptySet()) =
            Intake(IntakeKey.entries.map { values[it] ?: 0.0 }, vegGrams, pulseGrams, unknown)

        /** The sum of [intakes] (nothing for none). */
        fun sum(intakes: Iterable<Intake>): Intake = intakes.fold(NONE) { total, intake -> total + intake }
    }
}
