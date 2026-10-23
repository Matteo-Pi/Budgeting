package io.github.matteopi.budgeting.core.nutrition

import kotlinx.serialization.Serializable

/** Which Mifflin–St Jeor equation to use. */
enum class BodySex(val label: String) { FEMALE("Female"), MALE("Male") }

/** How active a typical day is, and the factor the resting energy is multiplied by. */
enum class Activity(val label: String, val factor: Double) {
    SITTING("Mostly sitting", 1.2),
    LIGHT("Light (walking, on your feet some of the day)", 1.375),
    MODERATE("Moderate (exercise 3 to 5 days a week)", 1.55),
    ACTIVE("Very active (hard exercise most days)", 1.725),
    ATHLETE("Athlete (training twice a day)", 1.9),
}

/** What the person wants their weight to do, and the kcal a day that changes. */
enum class WeightGoal(val label: String, val kcalPerDay: Double) {
    LOSE_HALF("Lose 0.5 kg a week", -550.0),
    LOSE_QUARTER("Lose 0.25 kg a week", -275.0),
    KEEP("Keep my weight", 0.0),
    GAIN_QUARTER("Gain 0.25 kg a week", 275.0),
}

/** What the calculator is given. */
@Serializable
data class BodyProfile(
    val sex: BodySex = BodySex.FEMALE,
    val age: Int = 20,
    val heightCm: Double = 170.0,
    val weightKg: Double = 65.0,
    val activity: Activity = Activity.LIGHT,
    val goal: WeightGoal = WeightGoal.KEEP,
    /** Protein for strength training: 1.6 g per kg instead of 0.75. */
    val strength: Boolean = false,
)

/** Daily targets worked out from a [BodyProfile] (docs/PLAN.md §14.4): a guide, not advice. */
object TargetCalculator {

    const val FLOOR_KCAL = 1200.0
    const val FLOOR_KCAL_MALE = 1500.0
    const val PROTEIN_PER_KG = 0.75
    const val PROTEIN_PER_KG_STRENGTH = 1.6
    const val FAT_SHARE = 0.30

    /** Resting energy (kcal a day) by Mifflin–St Jeor. */
    fun restingKcal(profile: BodyProfile): Double {
        val base = 10 * profile.weightKg + 6.25 * profile.heightCm - 5 * profile.age
        return base + if (profile.sex == BodySex.MALE) 5.0 else -161.0
    }

    /**
     * The targets: resting energy × activity ± the goal, never below 1,200 kcal (1,500 for men) and rounded to 10;
     * protein by weight; fat 30% of the energy; carbs the rest (never below 0); fibre 30 g; the limits as
     * [FoodTargets]' defaults, saturates at 10% of the energy.
     */
    fun targets(profile: BodyProfile): FoodTargets {
        val floor = if (profile.sex == BodySex.MALE) FLOOR_KCAL_MALE else FLOOR_KCAL
        val kcal = Math.round(maxOf(floor, restingKcal(profile) * profile.activity.factor + profile.goal.kcalPerDay) / 10.0) * 10.0
        val protein = Math.round(profile.weightKg * if (profile.strength) PROTEIN_PER_KG_STRENGTH else PROTEIN_PER_KG).toDouble()
        val fat = Math.round(kcal * FAT_SHARE / 9.0).toDouble()
        val carbs = Math.round(((kcal - protein * 4 - fat * 9) / 4.0).coerceAtLeast(0.0)).toDouble()
        val saturates = Math.round(kcal * 0.10 / 9.0).toDouble()
        return FoodTargets(kcal = kcal, protein = protein, carbs = carbs, fat = fat, saturates = saturates)
    }
}
