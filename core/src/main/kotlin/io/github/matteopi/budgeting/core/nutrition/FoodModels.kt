package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.ReferenceIntakes
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

/*
 * The food diary's and the kitchen's models (docs/PLAN.md §14.3, §14.4). The app's Room layer maps to and from
 * these. Logged numbers are frozen: a row keeps the nutrition and cost it had when it was eaten.
 */

/** The meals of a day, in order. */
enum class MealSlot(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACKS("Snacks"),
}

/** What a diary row is. */
enum class LogKind {
    /** A portion from the kitchen (a [Batch]). */
    BATCH,

    /** A portion of a recipe straight from the pan. */
    RECIPE,

    /** An ingredient or a food of the food list, by amount. */
    FOOD,

    /** Typed kcal and macros. */
    QUICK,
}

/**
 * One diary row. [amount] is portions for [LogKind.BATCH] and [LogKind.RECIPE], base units (g or ml) for
 * [LogKind.FOOD] and 1 for [LogKind.QUICK]; [amountText] is how it reads ("3 slices", "1½ portions"). [sideOf] is
 * the row this one is a side of (rice with a portion of chilli). [intake] and [costPence] are for the whole amount.
 */
data class LogItem(
    val id: Long = 0,
    val date: LocalDate,
    val meal: MealSlot,
    val eatenAtEpochMillis: Long = 0,
    val kind: LogKind,
    val name: String,
    val amount: Double = 1.0,
    val amountText: String = "",
    val recipeId: Long? = null,
    val batchId: Long? = null,
    val ingredientId: Long? = null,
    val sideOf: Long? = null,
    val costPence: Double? = null,
    val createdAtEpochMillis: Long = 0,
    val intake: Intake = Intake.NONE,
)

/**
 * One cooking of a recipe in the kitchen: [portions] made from [servings] of the recipe, with [fridgeLeft] and
 * [freezerLeft] portions left. [intake] and [costPence] are for **one portion**. [eatBy] is the fridge portions'
 * date; [frozenOn] when the first portion went into the freezer. [wasted] counts the portions thrown away.
 */
data class Batch(
    val id: Long = 0,
    val recipeId: Long? = null,
    val name: String,
    val madeOn: LocalDate,
    val servings: Double,
    val portions: Double,
    val fridgeLeft: Double,
    val freezerLeft: Double,
    val frozenOn: LocalDate? = null,
    val eatBy: LocalDate? = null,
    val wasted: Double = 0.0,
    val costPence: Double? = null,
    val note: String = "",
    val createdAtEpochMillis: Long = 0,
    val intake: Intake = Intake.NONE,
) {
    val left: Double get() = fridgeLeft + freezerLeft
    val finished: Boolean get() = left < Batches.EPSILON
}

/** A day's water and note. */
data class FoodDay(val date: LocalDate, val waterMl: Int = 0, val note: String = "")

/**
 * The daily targets. Energy, carbs and fat are aimed at; protein and fibre are "at least"; saturates, sugars,
 * added sugars and salt are limits. Vitamins and minerals use their references ([Micro.reference]).
 */
@Serializable
data class FoodTargets(
    val kcal: Double = ReferenceIntakes.KCAL,
    val protein: Double = ReferenceIntakes.PROTEIN,
    val carbs: Double = ReferenceIntakes.CARBS,
    val fat: Double = ReferenceIntakes.FAT,
    val saturates: Double = ReferenceIntakes.SATURATES,
    val sugars: Double = ReferenceIntakes.SUGARS,
    val addedSugars: Double = ReferenceIntakes.ADDED_SUGARS_LIMIT,
    val fibre: Double = ReferenceIntakes.FIBRE_GUIDELINE,
    val salt: Double = ReferenceIntakes.SALT,
) {
    operator fun get(nutrient: Nutrient): Double = when (nutrient) {
        Nutrient.KCAL -> kcal
        Nutrient.FAT -> fat
        Nutrient.SATURATES -> saturates
        Nutrient.CARBS -> carbs
        Nutrient.SUGARS -> sugars
        Nutrient.ADDED_SUGARS -> addedSugars
        Nutrient.FIBRE -> fibre
        Nutrient.PROTEIN -> protein
        Nutrient.SALT -> salt
    }

    /** The target or reference for any [IntakeKey]. */
    operator fun get(key: IntakeKey): Double = key.nutrient?.let { get(it) } ?: key.micro!!.reference

    companion object {
        /** How each nutrient's target is read. */
        fun goalOf(key: IntakeKey): TargetKind = when (key) {
            IntakeKey.KCAL, IntakeKey.CARBS, IntakeKey.FAT -> TargetKind.AIM
            IntakeKey.SATURATES, IntakeKey.SUGARS, IntakeKey.ADDED_SUGARS, IntakeKey.SALT -> TargetKind.LIMIT
            else -> TargetKind.AT_LEAST
        }
    }
}

/** [AIM]: about this much; [AT_LEAST]: this or more; [LIMIT]: no more than this. */
enum class TargetKind { AIM, AT_LEAST, LIMIT }

/**
 * The food settings (docs/PLAN.md §14.3), stored as one JSON value. The meal times are minutes of the day at which
 * a meal ends. [fridgeDays] is how long cooked food keeps in the fridge (2, the FSA's advice for leftovers).
 */
@Serializable
data class FoodPrefs(
    val targets: FoodTargets = FoodTargets(),
    /** True when the targets were worked out by [TargetCalculator]; [profile] is what it was given. */
    val profile: BodyProfile? = null,
    val breakfastUntil: Int = 10 * 60 + 30,
    val lunchUntil: Int = 15 * 60,
    val snacksUntil: Int = 17 * 60,
    val dinnerUntil: Int = 21 * 60 + 30,
    val fridgeDays: Int = 2,
    val freezerMonths: Int = 3,
    val glassMl: Int = 250,
    val waterGoalMl: Int = 2000,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** The stored prefs, or the defaults for none or a value that doesn't read. */
        fun decode(text: String?): FoodPrefs {
            if (text.isNullOrBlank()) return FoodPrefs()
            return try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                FoodPrefs()
            } catch (e: IllegalArgumentException) {
                FoodPrefs()
            }
        }
    }
}

/** Which meal a time of day falls in (docs/PLAN.md §14.4). */
object MealSlots {
    fun at(time: LocalTime, prefs: FoodPrefs = FoodPrefs()): MealSlot {
        val minute = time.hour * 60 + time.minute
        return when {
            minute < prefs.breakfastUntil -> MealSlot.BREAKFAST
            minute < prefs.lunchUntil -> MealSlot.LUNCH
            minute < prefs.snacksUntil -> MealSlot.SNACKS
            minute < prefs.dinnerUntil -> MealSlot.DINNER
            else -> MealSlot.SNACKS
        }
    }
}
