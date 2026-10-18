package io.github.matteopi.budgeting.ui.food.components

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.data.repo.BundleImportResult

/**
 * How the Recipes screens write numbers (docs/PLAN.md §11.2): energy to the whole number, grams to one
 * decimal place, money to the penny. Pure, so it is unit-tested on the JVM.
 */
object FoodFormat {

    /** "804 kcal". */
    fun kcal(value: Double): String = "${RecipeMaths.formatNutrient(Nutrient.KCAL, value)} kcal"

    /** "56.8 g". */
    fun grams(value: Double): String = "${RecipeMaths.formatNutrient(Nutrient.PROTEIN, value)} g"

    /** Energy in kilojoules to the whole number: "3753 kJ". */
    fun kj(kj: Double): String = "${Quantities.roundHalfUp(kj)} kJ"

    /** Veg portions to one decimal place: "6.1". */
    fun veg(value: Double): String = RecipeMaths.formatVeg(value)

    /** "45 min", "1 hr", "1 hr 15 min". */
    fun minutes(mins: Int): String = RecipeText.formatMinutes(mins)

    /** A percentage of a reference intake, whole number: "32%". */
    fun percent(value: Double): String = "${Quantities.roundHalfUp(value)}%"

    /** A share of a total as a percentage from a fraction of 0 to 1: 0.384 -> "38%". */
    fun share(fraction: Double): String = "${Quantities.roundHalfUp(fraction * 100)}%"

    /**
     * The cost of something: "£3.09", or "at least £2.10" when some ingredients have no price, or "no price yet"
     * when none of them has.
     */
    fun cost(amount: Money, atLeast: Boolean): String = when {
        atLeast && amount.pence <= 0L -> "no price yet"
        atLeast -> "at least ${amount.format()}"
        else -> amount.format()
    }

    /** The traffic light as a word, as on UK packs: green is Low, amber Med, red High. */
    fun lightLabel(light: Light): String = when (light) {
        Light.GREEN -> "Low"
        Light.AMBER -> "Med"
        Light.RED -> "High"
    }

    /** "1 recipe", "4 recipes". */
    fun count(n: Int, singular: String, plural: String = singular + "s"): String = "$n ${if (n == 1) singular else plural}"

    /** "Rated 4 out of 5", or "Not rated yet". */
    fun ratingText(rating: Int): String = if (rating <= 0) "Not rated yet" else "Rated $rating out of 5"

    /** "1 price is old", "3 prices are old". */
    fun oldPrices(n: Int): String = if (n == 1) "1 price is old" else "$n prices are old"

    /** "1 ingredient has no price", "2 ingredients have no price". */
    fun missingPrices(n: Int): String = if (n == 1) "1 ingredient has no price" else "$n ingredients have no price"

    /** "At least: 1 ingredient has no nutrition". */
    fun missingNutrition(n: Int): String =
        if (n == 1) "At least: 1 ingredient has no nutrition" else "At least: $n ingredients have no nutrition"

    /** "Added sugars are at least this: 1 ingredient doesn't say how much it has". */
    fun missingAddedSugars(n: Int): String =
        "Added sugars are at least this: " + if (n == 1) "1 ingredient doesn't say how much it has" else "$n ingredients don't say how much they have"

    /**
     * What the snackbar says after a bundle import: "Added 4 recipes and 40 new ingredients", or with ingredients that
     * were matched to ones the user already had, "Added 4 recipes with 37 new ingredients (and 3 you already had)".
     */
    fun importSummary(result: BundleImportResult): String {
        val recipes = count(result.recipeIds.size, "recipe")
        val added = result.ingredientsAdded
        val reused = result.ingredientsReused
        return when {
            added > 0 && reused > 0 -> "Added $recipes with ${count(added, "new ingredient")} (and $reused you already had)"
            added > 0 -> "Added $recipes and ${count(added, "new ingredient")}"
            reused > 0 -> "Added $recipes using ${count(reused, "ingredient")} you already had"
            else -> "Added $recipes"
        }
    }
}
