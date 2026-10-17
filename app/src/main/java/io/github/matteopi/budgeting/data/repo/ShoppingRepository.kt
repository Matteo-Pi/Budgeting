package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.recipes.ShoppingEntry
import io.github.matteopi.budgeting.core.recipes.ShoppingMark
import io.github.matteopi.budgeting.data.db.dao.FoodDao
import io.github.matteopi.budgeting.domain.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The shopping list's stored parts (docs/PLAN.md §11.6): its entries and the ticks of its
 * ingredient lines. The list itself (sums, packs, costs, shelves) is `ShoppingList.build` in
 * `core.recipes`, fed from these flows plus the recipes, ingredients and prices.
 *
 * An entry is a recipe at some servings, an extra ingredient with a quantity, or free text. Ticks
 * for ingredient lines, which sum several entries, are [ShoppingMark]s; free-text items carry their
 * own tick ([ShoppingEntry.checked]).
 */
class ShoppingRepository(
    private val foodDao: FoodDao,
    private val clock: Clock,
) {
    /** The entries in the order they were added. */
    fun observeEntries(): Flow<List<ShoppingEntry>> =
        foodDao.observeShoppingEntries().map { list -> list.map { it.toModel() } }

    fun observeMarks(): Flow<List<ShoppingMark>> =
        foodDao.observeShoppingMarks().map { list -> list.map { it.toModel() } }

    /**
     * Adds [recipeId] at [servings] (at least 1). Adding the same recipe again adds a second entry,
     * and the list sums them. Returns the new entry's id.
     */
    suspend fun addRecipe(recipeId: Long, servings: Int): Long = foodDao.insertShoppingEntry(
        ShoppingEntry(recipeId = recipeId, servings = servings.coerceAtLeast(1), addedAtEpochMillis = clock.nowMillis()).toEntity(),
    )

    /**
     * Adds an extra ingredient: [quantity] in its base unit, or null for one whole pack. Returns the
     * new entry's id.
     */
    suspend fun addIngredient(ingredientId: Long, quantity: Double? = null): Long = foodDao.insertShoppingEntry(
        ShoppingEntry(
            ingredientId = ingredientId,
            quantity = quantity?.takeIf { it > 0.0 },
            addedAtEpochMillis = clock.nowMillis(),
        ).toEntity(),
    )

    /** Adds a free-text item such as "Bin bags". Blank text adds nothing and returns null. */
    suspend fun addFreeText(text: String): Long? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        return foodDao.insertShoppingEntry(
            ShoppingEntry(freeText = clean, addedAtEpochMillis = clock.nowMillis()).toEntity(),
        )
    }

    /** Changes the servings of a recipe entry. */
    suspend fun setServings(entryId: Long, servings: Int) {
        foodDao.setShoppingEntryServings(entryId, servings.coerceAtLeast(1))
    }

    /** Ticks or unticks a free-text item. Ingredient lines are ticked with [setChecked]. */
    suspend fun setFreeTextChecked(entryId: Long, checked: Boolean) {
        foodDao.setShoppingEntryChecked(entryId, checked)
    }

    suspend fun removeEntry(entryId: Long) {
        foodDao.deleteShoppingEntry(entryId)
    }

    /** Ticks or unticks the shopping list line of [ingredientId]. */
    suspend fun setChecked(ingredientId: Long, checked: Boolean) {
        foodDao.setShoppingMark(ingredientId, checked = checked, haveIt = null)
    }

    /** Marks the line of [ingredientId] "I have it" (nothing to buy) or buys it again. */
    suspend fun setHaveIt(ingredientId: Long, haveIt: Boolean) {
        foodDao.setShoppingMark(ingredientId, checked = null, haveIt = haveIt)
    }

    /**
     * *Clear ticked*: removes the free-text items that are ticked and resets every mark (the ticks and
     * "I have it"), in one transaction. Recipe and extra-ingredient entries stay.
     */
    suspend fun clearTicked() {
        foodDao.clearTickedShopping()
    }

    /** *Clear list*: removes every entry and every mark, in one transaction. */
    suspend fun clearList() {
        foodDao.clearShoppingList()
    }
}
