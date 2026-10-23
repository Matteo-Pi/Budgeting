package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import io.github.matteopi.budgeting.core.recipes.VegKind

/** A portion's or an amount's intake together with its cost in pence (null: nothing of it has a price). */
data class Eaten(val intake: Intake, val costPence: Double?, val unpriced: Int = 0)

/** What foods, recipe portions and quick entries add to the diary (docs/PLAN.md §14.4). */
object Intakes {

    /**
     * [amount] base units (g or ml) of [ingredient]: its nutrition and vitamins per 100 scaled, unknown values
     * counted as 0 and named; added sugars by [RecipeMaths.addedSugarsPer100]; veg and pulse grams by its veg kind.
     */
    fun ofFood(ingredient: Ingredient, amount: Double): Intake {
        val unknown = LinkedHashSet<IntakeKey>()
        val amounts = IntakeKey.entries.map { key ->
            val per100 = when {
                key == IntakeKey.ADDED_SUGARS -> RecipeMaths.addedSugarsPer100(ingredient)
                key.nutrient != null -> ingredient.per100[key.nutrient]
                else -> ingredient.micros[key.micro!!]
            }
            if (per100 == null) {
                unknown += key
                0.0
            } else {
                amount / 100.0 * per100
            }
        }
        return Intake(
            amounts = amounts,
            vegGrams = if (ingredient.vegKind == VegKind.VEG) amount else 0.0,
            pulseGrams = if (ingredient.vegKind == VegKind.PULSE) amount else 0.0,
            unknown = unknown,
        )
    }

    /** [ofFood] with its cost: [amount] × the price per base unit, or null with no price. */
    fun eatFood(ingredient: Ingredient, amount: Double, price: ResolvedPrice?): Eaten =
        Eaten(ofFood(ingredient, amount), price?.let { it.perUnitPence * amount }, if (price == null) 1 else 0)

    /**
     * One portion of [recipe] cooked for [servings] (its own by default) and split into [portions] (as many as the
     * servings by default). A line counts as in [RecipeMaths.analyse]: it points at one of [ingredients], has a
     * quantity, and is not optional unless [countOptional]. The cost leaves out ingredients with no price and
     * counts them in [Eaten.unpriced].
     */
    fun portionOf(
        recipe: Recipe,
        ingredients: Map<Long, Ingredient>,
        prices: Map<Long, ResolvedPrice> = emptyMap(),
        servings: Double = recipe.serves.toDouble(),
        portions: Double = servings,
        countOptional: Boolean = true,
    ): Eaten {
        val factor = servings / recipe.serves.coerceAtLeast(1)
        val split = portions.coerceAtLeast(MIN_PORTIONS)
        var total = Intake.NONE
        var pence = 0.0
        var priced = false
        val unpriced = LinkedHashSet<Long>()
        for (line in recipe.lines) {
            val id = line.ingredientId ?: continue
            val quantity = line.quantity ?: continue
            val ingredient = ingredients[id] ?: continue
            if (line.optional && !countOptional) continue
            val amount = quantity * factor
            total += ofFood(ingredient, amount)
            val price = prices[id]
            if (price != null) {
                pence += price.perUnitPence * amount
                priced = true
            } else {
                unpriced += id
            }
        }
        return Eaten(total / split, if (priced) pence / split else null, unpriced.size)
    }

    /**
     * A typed entry: [kcal] and whichever macros were given. Everything not given is unknown, so the day's totals
     * of it read "at least".
     */
    fun quick(kcal: Double, protein: Double? = null, carbs: Double? = null, fat: Double? = null, sugars: Double? = null, salt: Double? = null): Intake {
        val given = mapOf(
            IntakeKey.KCAL to kcal,
            IntakeKey.PROTEIN to protein,
            IntakeKey.CARBS to carbs,
            IntakeKey.FAT to fat,
            IntakeKey.SUGARS to sugars,
            IntakeKey.SALT to salt,
        )
        val known = given.filterValues { it != null }.mapValues { it.value!! }
        return Intake.of(known, unknown = IntakeKey.entries.filter { it !in known }.toSet())
    }

    /** The intake of one [nutrient] only, for tests and the quick sheet. */
    fun only(nutrient: Nutrient, amount: Double): Intake = Intake.of(mapOf(IntakeKey.of(nutrient) to amount))

    /** A portion count can't be split below this. */
    private const val MIN_PORTIONS = 0.25
}
