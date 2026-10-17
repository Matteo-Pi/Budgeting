package io.github.matteopi.budgeting.core.recipes

enum class Light { GREEN, AMBER, RED }

/**
 * Front-of-pack traffic lights for fat, saturates, sugars (total) and salt (docs/PLAN.md §11.2).
 *
 * | | Green (per 100 g) | Red (per 100 g) | Red (per portion) |
 * |---|---|---|---|
 * | Fat | ≤ 3.0 | > 17.5 | > 21 |
 * | Saturates | ≤ 1.5 | > 5.0 | > 6.0 |
 * | Sugars | ≤ 5.0 | > 22.5 | > 27 |
 * | Salt | ≤ 0.3 | > 1.5 | > 1.8 |
 *
 * Between green and red per 100 g is amber. The colour is red if either rule says red, else the
 * per 100 g colour. Energy, carbohydrate, fibre and protein have no colour.
 */
object TrafficLights {

    /** Values within this of a threshold count as on it, so that 3.0000000000000004 is still "3.0". */
    private const val EPSILON = 1e-9

    private class Limits(val green: Double, val redPer100: Double, val redPerPortion: Double)

    private val LIMITS = mapOf(
        Nutrient.FAT to Limits(3.0, 17.5, 21.0),
        Nutrient.SATURATES to Limits(1.5, 5.0, 6.0),
        Nutrient.SUGARS to Limits(5.0, 22.5, 27.0),
        Nutrient.SALT to Limits(0.3, 1.5, 1.8),
    )

    /** The nutrients that get a colour. */
    val coloured: List<Nutrient> = listOf(Nutrient.FAT, Nutrient.SATURATES, Nutrient.SUGARS, Nutrient.SALT)

    /** Green up to and including the green limit, red above the red limit, amber between. Null for a nutrient with no colour. */
    fun per100(nutrient: Nutrient, gramsPer100: Double): Light? {
        val limits = LIMITS[nutrient] ?: return null
        return when {
            gramsPer100 <= limits.green + EPSILON -> Light.GREEN
            gramsPer100 > limits.redPer100 + EPSILON -> Light.RED
            else -> Light.AMBER
        }
    }

    /** True when a portion holds more than 30% of the reference intake. */
    fun redPerPortion(nutrient: Nutrient, gramsPerPortion: Double): Boolean {
        val limits = LIMITS[nutrient] ?: return false
        return gramsPerPortion > limits.redPerPortion + EPSILON
    }

    /** The colour: red if either the per 100 g or the per portion rule says red, else the per 100 g colour. */
    fun colour(nutrient: Nutrient, gramsPer100: Double, gramsPerPortion: Double): Light? {
        val base = per100(nutrient, gramsPer100) ?: return null
        return if (redPerPortion(nutrient, gramsPerPortion)) Light.RED else base
    }
}
