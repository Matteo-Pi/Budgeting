package io.github.matteopi.budgeting.ui.food.recipe

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientLines
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.VegKind

/** One ingredient line as the recipe screen shows it at the chosen servings. */
data class DetailLine(
    val group: String?,
    /** The line as written, rewritten for the servings when they differ from the recipe's own. */
    val text: String,
    val optional: Boolean,
    /** The line has an ingredient and an amount, so it counts towards the numbers. */
    val counted: Boolean,
)

/** Building what the recipe screen shows. Pure, so it is unit-tested on the JVM. */
object RecipeDetailMapping {

    /** The lines of [recipe] at [servings]: scaled text when the servings differ from the recipe's own. */
    fun lines(recipe: Recipe, ingredients: Map<Long, Ingredient>, servings: Int): List<DetailLine> {
        val factor = servings.toDouble() / recipe.serves.coerceAtLeast(1)
        val scaled = servings != recipe.serves
        return recipe.lines.map { line ->
            val ingredient = line.ingredientId?.let { ingredients[it] }
            val counted = ingredient != null && line.quantity != null
            DetailLine(
                group = line.group?.takeIf { it.isNotBlank() },
                text = if (scaled) IngredientLines.scaleText(line.text, factor, line.quantity, ingredient?.unit) else line.text,
                optional = line.optional,
                counted = counted,
            )
        }
    }

    /** True when any counted ingredient of [recipe] is a pulse, so the screen explains the one-portion cap. */
    fun hasPulses(recipe: Recipe, ingredients: Map<Long, Ingredient>): Boolean =
        recipe.lines.any { line -> line.ingredientId?.let { ingredients[it] }?.vegKind == VegKind.PULSE }
}
