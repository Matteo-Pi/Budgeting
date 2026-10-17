package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.IngredientAliasEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientEntity
import io.github.matteopi.budgeting.data.db.entities.IngredientPortionEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeLineEntity
import io.github.matteopi.budgeting.data.db.entities.RecipeStepEntity
import io.github.matteopi.budgeting.data.db.entities.ShoppingEntryEntity
import io.github.matteopi.budgeting.data.db.entities.ShoppingMarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * Ingredients, recipes and the shopping list (docs/PLAN.md §11.3). An abstract class rather than an
 * interface so the writes that touch several tables can be `@Transaction` methods with a body:
 * [saveRecipe] and [saveIngredient] replace a parent and its children atomically.
 *
 * Reads that combine tables ([IngredientWithPortions], [RecipeWithParts]) are single transactions,
 * so a flow never shows a recipe next to the lines of an older save.
 */
@Dao
abstract class FoodDao {
    // ---- Ingredients ------------------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM ingredients ORDER BY name COLLATE NOCASE, id")
    abstract fun observeIngredients(): Flow<List<IngredientWithPortions>>

    @Transaction
    @Query("SELECT * FROM ingredients WHERE id = :id")
    abstract fun observeIngredient(id: Long): Flow<IngredientWithPortions?>

    @Transaction
    @Query("SELECT * FROM ingredients ORDER BY name COLLATE NOCASE, id")
    abstract suspend fun getIngredients(): List<IngredientWithPortions>

    @Transaction
    @Query("SELECT * FROM ingredients WHERE id = :id")
    abstract suspend fun getIngredient(id: Long): IngredientWithPortions?

    @Transaction
    @Query("SELECT * FROM ingredients WHERE barcode = :barcode LIMIT 1")
    abstract suspend fun findIngredientByBarcode(barcode: String): IngredientWithPortions?

    @Insert
    abstract suspend fun insertIngredient(ingredient: IngredientEntity): Long

    @Insert
    abstract suspend fun insertIngredients(ingredients: List<IngredientEntity>): List<Long>

    /** Returns the number of rows changed: 0 when there is no such ingredient. */
    @Update
    abstract suspend fun updateIngredient(ingredient: IngredientEntity): Int

    @Query("UPDATE ingredients SET archived = :archived WHERE id = :id")
    abstract suspend fun setIngredientArchived(id: Long, archived: Boolean)

    @Query("UPDATE ingredients SET product_id = :productId WHERE id = :id")
    abstract suspend fun setIngredientProduct(id: Long, productId: Long?)

    /** Portions, aliases, shopping entries and marks go with it (CASCADE); recipe lines keep their row (SET_NULL). */
    @Query("DELETE FROM ingredients WHERE id = :id")
    abstract suspend fun deleteIngredient(id: Long)

    @Query("DELETE FROM ingredients")
    abstract suspend fun deleteAllIngredients()

    @Query("SELECT COUNT(*) FROM recipe_lines WHERE ingredient_id = :id")
    abstract suspend fun countLinesUsingIngredient(id: Long): Int

    @Insert
    abstract suspend fun insertPortions(portions: List<IngredientPortionEntity>): List<Long>

    @Query("DELETE FROM ingredient_portions WHERE ingredient_id = :ingredientId")
    abstract suspend fun deletePortions(ingredientId: Long)

    @Query("DELETE FROM ingredient_portions")
    abstract suspend fun deleteAllPortions()

    /**
     * Saves an ingredient and replaces its portions, in one transaction. An entity with id 0 is
     * inserted; any other id is updated (an unknown id is an error). Returns the ingredient's id.
     */
    @Transaction
    open suspend fun saveIngredient(ingredient: IngredientEntity, portions: List<IngredientPortionEntity>): Long {
        val id = if (ingredient.id == 0L) {
            insertIngredient(ingredient)
        } else {
            val changed = updateIngredient(ingredient)
            require(changed > 0) { "There is no ingredient ${ingredient.id} to update" }
            deletePortions(ingredient.id)
            ingredient.id
        }
        if (portions.isNotEmpty()) {
            insertPortions(portions.map { it.copy(id = 0, ingredientId = id) })
        }
        return id
    }

    /**
     * Deletes the ingredient only if no recipe line points at it (ingredients are archived, never
     * deleted, while a recipe uses them). Returns whether it was deleted.
     */
    @Transaction
    open suspend fun deleteIngredientIfUnused(id: Long): Boolean {
        if (countLinesUsingIngredient(id) > 0) return false
        deleteIngredient(id)
        return true
    }

    // ---- Ingredient aliases -----------------------------------------------------------------

    @Query("SELECT * FROM ingredient_aliases ORDER BY name_key")
    abstract fun observeIngredientAliases(): Flow<List<IngredientAliasEntity>>

    @Query("SELECT * FROM ingredient_aliases ORDER BY name_key")
    abstract suspend fun getIngredientAliases(): List<IngredientAliasEntity>

    @Query("SELECT * FROM ingredient_aliases WHERE name_key = :nameKey LIMIT 1")
    abstract suspend fun getIngredientAliasByKey(nameKey: String): IngredientAliasEntity?

    /** Returns the new row id, or -1 when the name key is already taken (nothing is changed). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIngredientAliasIfFree(alias: IngredientAliasEntity): Long

    @Insert
    abstract suspend fun insertIngredientAliases(aliases: List<IngredientAliasEntity>): List<Long>

    @Query("UPDATE ingredient_aliases SET ingredient_id = :ingredientId WHERE id = :id")
    abstract suspend fun setIngredientAliasTarget(id: Long, ingredientId: Long)

    @Query("DELETE FROM ingredient_aliases WHERE id = :id")
    abstract suspend fun deleteIngredientAlias(id: Long)

    @Query("DELETE FROM ingredient_aliases")
    abstract suspend fun deleteAllIngredientAliases()

    /** Makes [nameKey] mean [ingredientId]: re-points the alias if the key is taken, else adds it. */
    @Transaction
    open suspend fun mapIngredientAlias(nameKey: String, ingredientId: Long) {
        val existing = getIngredientAliasByKey(nameKey)
        if (existing == null) {
            insertIngredientAliasIfFree(IngredientAliasEntity(nameKey = nameKey, ingredientId = ingredientId))
        } else if (existing.ingredientId != ingredientId) {
            setIngredientAliasTarget(existing.id, ingredientId)
        }
    }

    // ---- Recipes ----------------------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM recipes ORDER BY title COLLATE NOCASE, id")
    abstract fun observeRecipes(): Flow<List<RecipeWithParts>>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract fun observeRecipe(id: Long): Flow<RecipeWithParts?>

    @Transaction
    @Query("SELECT * FROM recipes ORDER BY title COLLATE NOCASE, id")
    abstract suspend fun getRecipes(): List<RecipeWithParts>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun getRecipe(id: Long): RecipeWithParts?

    @Insert
    abstract suspend fun insertRecipe(recipe: RecipeEntity): Long

    @Insert
    abstract suspend fun insertRecipes(recipes: List<RecipeEntity>): List<Long>

    /** Returns the number of rows changed: 0 when there is no such recipe. */
    @Update
    abstract suspend fun updateRecipe(recipe: RecipeEntity): Int

    @Insert
    abstract suspend fun insertRecipeLines(lines: List<RecipeLineEntity>): List<Long>

    @Insert
    abstract suspend fun insertRecipeSteps(steps: List<RecipeStepEntity>): List<Long>

    @Query("DELETE FROM recipe_lines WHERE recipe_id = :recipeId")
    abstract suspend fun deleteRecipeLines(recipeId: Long)

    @Query("DELETE FROM recipe_steps WHERE recipe_id = :recipeId")
    abstract suspend fun deleteRecipeSteps(recipeId: Long)

    @Query("DELETE FROM recipe_lines")
    abstract suspend fun deleteAllRecipeLines()

    @Query("DELETE FROM recipe_steps")
    abstract suspend fun deleteAllRecipeSteps()

    @Query("UPDATE recipes SET archived = :archived WHERE id = :id")
    abstract suspend fun setRecipeArchived(id: Long, archived: Boolean)

    @Query("UPDATE recipes SET rating = :rating, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun setRecipeRating(id: Long, rating: Int, updatedAt: Long)

    @Query("UPDATE recipes SET notes = :notes, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun setRecipeNotes(id: Long, notes: String, updatedAt: Long)

    /** Lines, steps and shopping entries go with it (CASCADE). */
    @Query("DELETE FROM recipes WHERE id = :id")
    abstract suspend fun deleteRecipe(id: Long)

    @Query("DELETE FROM recipes")
    abstract suspend fun deleteAllRecipes()

    /**
     * Saves a recipe with its lines and steps, in one transaction. An entity with id 0 is
     * inserted; any other id is updated (an unknown id is an error) and its lines and steps are
     * replaced. [lines] and [steps] are stored in the order given. Returns the recipe's id.
     *
     * The recipe is updated in place, never deleted and re-inserted, so its shopping list entries
     * (which cascade) survive.
     */
    @Transaction
    open suspend fun saveRecipe(
        recipe: RecipeEntity,
        lines: List<RecipeLineEntity>,
        steps: List<RecipeStepEntity>,
    ): Long {
        val id = if (recipe.id == 0L) {
            insertRecipe(recipe)
        } else {
            val changed = updateRecipe(recipe)
            require(changed > 0) { "There is no recipe ${recipe.id} to update" }
            deleteRecipeLines(recipe.id)
            deleteRecipeSteps(recipe.id)
            recipe.id
        }
        if (lines.isNotEmpty()) {
            insertRecipeLines(lines.mapIndexed { index, line -> line.copy(id = 0, recipeId = id, position = index) })
        }
        if (steps.isNotEmpty()) {
            insertRecipeSteps(steps.mapIndexed { index, step -> step.copy(id = 0, recipeId = id, position = index) })
        }
        return id
    }

    // ---- Shopping list ----------------------------------------------------------------------

    @Query("SELECT * FROM shopping_entries ORDER BY added_at, id")
    abstract fun observeShoppingEntries(): Flow<List<ShoppingEntryEntity>>

    @Query("SELECT * FROM shopping_entries ORDER BY added_at, id")
    abstract suspend fun getShoppingEntries(): List<ShoppingEntryEntity>

    @Insert
    abstract suspend fun insertShoppingEntry(entry: ShoppingEntryEntity): Long

    @Insert
    abstract suspend fun insertShoppingEntries(entries: List<ShoppingEntryEntity>): List<Long>

    @Query("UPDATE shopping_entries SET servings = :servings WHERE id = :id")
    abstract suspend fun setShoppingEntryServings(id: Long, servings: Int)

    @Query("UPDATE shopping_entries SET checked = :checked WHERE id = :id")
    abstract suspend fun setShoppingEntryChecked(id: Long, checked: Boolean)

    @Query("DELETE FROM shopping_entries WHERE id = :id")
    abstract suspend fun deleteShoppingEntry(id: Long)

    /** Free-text items that are ticked; recipe and ingredient entries never use `checked`. */
    @Query("DELETE FROM shopping_entries WHERE recipe_id IS NULL AND ingredient_id IS NULL AND checked = 1")
    abstract suspend fun deleteTickedFreeTextEntries()

    @Query("DELETE FROM shopping_entries")
    abstract suspend fun deleteAllShoppingEntries()

    @Query("SELECT * FROM shopping_marks ORDER BY ingredient_id")
    abstract fun observeShoppingMarks(): Flow<List<ShoppingMarkEntity>>

    @Query("SELECT * FROM shopping_marks ORDER BY ingredient_id")
    abstract suspend fun getShoppingMarks(): List<ShoppingMarkEntity>

    @Query("SELECT * FROM shopping_marks WHERE ingredient_id = :ingredientId")
    abstract suspend fun getShoppingMark(ingredientId: Long): ShoppingMarkEntity?

    @Insert
    abstract suspend fun insertShoppingMark(mark: ShoppingMarkEntity): Long

    @Insert
    abstract suspend fun insertShoppingMarks(marks: List<ShoppingMarkEntity>): List<Long>

    @Query("UPDATE shopping_marks SET checked = :checked, have_it = :haveIt WHERE id = :id")
    abstract suspend fun updateShoppingMark(id: Long, checked: Boolean, haveIt: Boolean)

    @Query("DELETE FROM shopping_marks")
    abstract suspend fun deleteAllShoppingMarks()

    /**
     * Changes the ticks of one ingredient line: a null argument leaves that tick as it is. The
     * ingredient's row is created on the first tick.
     */
    @Transaction
    open suspend fun setShoppingMark(ingredientId: Long, checked: Boolean?, haveIt: Boolean?) {
        val existing = getShoppingMark(ingredientId)
        if (existing == null) {
            insertShoppingMark(
                ShoppingMarkEntity(ingredientId = ingredientId, checked = checked ?: false, haveIt = haveIt ?: false),
            )
        } else {
            updateShoppingMark(existing.id, checked ?: existing.checked, haveIt ?: existing.haveIt)
        }
    }

    /** Removes the free-text items that are ticked and resets every mark (the ticks and "I have it"). */
    @Transaction
    open suspend fun clearTickedShopping() {
        deleteTickedFreeTextEntries()
        deleteAllShoppingMarks()
    }

    /** Empties the whole list: every entry and every mark. */
    @Transaction
    open suspend fun clearShoppingList() {
        deleteAllShoppingEntries()
        deleteAllShoppingMarks()
    }
}
