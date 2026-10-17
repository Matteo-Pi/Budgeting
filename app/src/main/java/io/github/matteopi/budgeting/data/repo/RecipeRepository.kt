package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.recipes.BundleMerge
import io.github.matteopi.budgeting.core.recipes.IngredientMatching
import io.github.matteopi.budgeting.core.recipes.IngredientMerge
import io.github.matteopi.budgeting.core.recipes.MergeChoice
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.FoodDao
import io.github.matteopi.budgeting.data.db.entities.IngredientAliasEntity
import io.github.matteopi.budgeting.domain.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What [RecipeRepository.importBundle] added. */
data class BundleImportResult(
    /** The new recipes' ids, in the order they are in the file. */
    val recipeIds: List<Long>,
    /** Ingredients added to the cupboard. */
    val ingredientsAdded: Int,
    /** Bundle ingredients that were pointed at an ingredient the user already had. */
    val ingredientsReused: Int,
)

/**
 * Recipes with their lines and steps (docs/PLAN.md §11.3), and importing a recipe bundle with the
 * §11.7 merge rules. A recipe is read and written as a whole: `core.recipes.Recipe` carries its
 * lines and its steps, tips, equipment and storage notes in order.
 *
 * The reads include archived recipes; each has [Recipe.archived] set.
 */
class RecipeRepository(
    private val database: BudgetDatabase,
    private val foodDao: FoodDao,
    private val clock: Clock,
) {
    /** Every recipe with its lines and steps, by title (case ignored). */
    fun observeAll(): Flow<List<Recipe>> = foodDao.observeRecipes().map { list -> list.map { it.toModel() } }

    fun observe(id: Long): Flow<Recipe?> = foodDao.observeRecipe(id).map { it?.toModel() }

    suspend fun get(id: Long): Recipe? = foodDao.getRecipe(id)?.toModel()

    /**
     * Saves [recipe] with its lines and steps in one transaction and returns its id. A recipe with
     * id 0 is added; any other id updates that recipe (which must exist), replacing its lines and
     * steps, and leaves its shopping list entries alone. [Recipe.updatedAtEpochMillis] becomes now;
     * [Recipe.createdAtEpochMillis] is kept (now for a new recipe that has none).
     *
     * Tidied on the way in: the title is trimmed (and must not be blank), serves is at least 1, the
     * rating is within 0 to 5, and blank optional text becomes null.
     */
    suspend fun save(recipe: Recipe): Long {
        val title = recipe.title.trim()
        require(title.isNotEmpty()) { "A recipe needs a title" }
        val now = clock.nowMillis()
        val createdAt = when {
            recipe.createdAtEpochMillis > 0 -> recipe.createdAtEpochMillis
            recipe.id != 0L -> foodDao.getRecipe(recipe.id)?.recipe?.createdAt ?: now
            else -> now
        }
        val tidy = recipe.copy(
            title = title,
            shortTitle = recipe.shortTitle.blankToNull(),
            tagline = recipe.tagline.blankToNull(),
            style = recipe.style.blankToNull(),
            serves = recipe.serves.coerceAtLeast(1),
            timeNote = recipe.timeNote.blankToNull(),
            servedWith = recipe.servedWith.blankToNull(),
            rating = recipe.rating.coerceIn(0, 5),
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = now,
        )
        return foodDao.saveRecipe(tidy.toEntity(), tidy.toLineEntities(), tidy.toStepEntities())
    }

    /** Hides the recipe from the lists (or brings it back). Nothing else changes. */
    suspend fun setArchived(id: Long, archived: Boolean) {
        foodDao.setRecipeArchived(id, archived)
    }

    /** Sets the star rating (0 = not rated, clamped to 0 to 5). */
    suspend fun setRating(id: Long, rating: Int) {
        foodDao.setRecipeRating(id, rating.coerceIn(0, 5), clock.nowMillis())
    }

    suspend fun setNotes(id: Long, notes: String) {
        foodDao.setRecipeNotes(id, notes, clock.nowMillis())
    }

    /** Deletes the recipe, its lines and steps, and its entries on the shopping list. */
    suspend fun delete(id: Long) {
        foodDao.deleteRecipe(id)
    }

    // ---- Importing a recipe bundle ----------------------------------------------------------

    /**
     * The bundle's ingredients that the user already has, by name or learned alias (§11.7), so the
     * screen can offer *Use existing* or *Import as new* for each before calling [importBundle].
     * Archived ingredients and merely similar names are not matched.
     */
    suspend fun findMatches(bundle: RecipeBundle): List<IngredientMerge> {
        val existing = foodDao.getIngredients().map { it.toModel() }
        val aliases = foodDao.getIngredientAliases().associate { it.nameKey to it.ingredientId }
        return BundleMerge.matches(bundle.toModels(), existing, aliases)
    }

    /**
     * Adds a bundle's ingredients and recipes in one transaction (all or nothing).
     *
     * - A bundle ingredient that matches one the user already has (see [findMatches]) is not added
     *   when its [decisions] entry (by the ingredient's key in the file) is [MergeChoice.USE_EXISTING],
     *   which is also what a missing entry means; its recipe lines point at the existing ingredient.
     *   With [MergeChoice.IMPORT_AS_NEW], or when nothing matches, it is added.
     * - Every recipe line's ingredient is remapped from the bundle's provisional id to the real one
     *   before the recipe is saved, so lines never point at the wrong ingredient.
     * - Each bundle ingredient's name is learned as an alias of the ingredient it ended up as, unless
     *   that name is already taken.
     * - Recipes are always added (importing the same book twice gives two copies), with the source
     *   the bundle says; they keep the bundle's added date as their creation time (now if it has
     *   none) and are updated now.
     *
     * [bundle] should come from `RecipeBundle.decode` or `RecipeBundle.starter()`, which check it.
     */
    suspend fun importBundle(
        bundle: RecipeBundle,
        decisions: Map<String, MergeChoice> = emptyMap(),
    ): BundleImportResult = database.withTransaction {
        val models = bundle.toModels()
        val now = clock.nowMillis()
        val existing = foodDao.getIngredients().map { it.toModel() }
        val aliases = foodDao.getIngredientAliases().associate { it.nameKey to it.ingredientId }
        val matchByProvisionalId = BundleMerge.matches(models, existing, aliases).associateBy { it.bundle.id }

        val realIds = HashMap<Long, Long>()
        var added = 0
        var reused = 0
        for (ingredient in models.ingredients) {
            val match = matchByProvisionalId[ingredient.id]
            val choice = match?.let { decisions[it.key] } ?: MergeChoice.USE_EXISTING
            val realId = if (match != null && choice == MergeChoice.USE_EXISTING) {
                reused++
                match.existing.id
            } else {
                added++
                val fresh = ingredient.copy(id = 0, createdAtEpochMillis = now)
                foodDao.saveIngredient(fresh.toEntity(), fresh.toPortionEntities())
            }
            realIds[ingredient.id] = realId
            val nameKey = IngredientMatching.nameKey(ingredient.name)
            if (nameKey.isNotEmpty()) {
                foodDao.insertIngredientAliasIfFree(IngredientAliasEntity(nameKey = nameKey, ingredientId = realId))
            }
        }

        val recipeIds = ArrayList<Long>(models.recipes.size)
        for (recipe in models.recipes) {
            val remapped = recipe.copy(
                id = 0,
                createdAtEpochMillis = if (recipe.createdAtEpochMillis > 0) recipe.createdAtEpochMillis else now,
                updatedAtEpochMillis = now,
                lines = recipe.lines.map { line -> line.copy(ingredientId = line.ingredientId?.let { realIds[it] }) },
            )
            recipeIds += foodDao.saveRecipe(remapped.toEntity(), remapped.toLineEntities(), remapped.toStepEntities())
        }
        BundleImportResult(recipeIds, added, reused)
    }
}
