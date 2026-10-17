package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Embedded
import androidx.room.Relation
import io.github.matteopi.budgeting.data.db.entities.IngredientEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientPortionEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeLineEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeStepEntity

/**
 * An ingredient with its portions, read in one transaction so a flow never shows an ingredient
 * next to the portions of an older save. Room does not promise an order for the portions; the
 * mapper sorts them.
 */
data class IngredientWithPortions(
    @Embedded val ingredient: IngredientEntity,
    @Relation(parentColumn = "id", entityColumn = "ingredient_id")
    val portions: List<IngredientPortionEntity>,
)

/**
 * A recipe with its ingredient lines and its steps, read in one transaction. Room does not promise
 * an order for [lines] and [steps]; the mapper sorts them by `position`.
 */
data class RecipeWithParts(
    @Embedded val recipe: RecipeEntity,
    @Relation(parentColumn = "id", entityColumn = "recipe_id")
    val lines: List<RecipeLineEntity>,
    @Relation(parentColumn = "id", entityColumn = "recipe_id")
    val steps: List<RecipeStepEntity>,
)
