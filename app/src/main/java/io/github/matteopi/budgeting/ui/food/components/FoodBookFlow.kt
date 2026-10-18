package io.github.matteopi.budgeting.ui.food.components

import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.ui.insights.observeSnapshot
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * The [FoodBook], emitting again whenever a recipe, an ingredient, what the kitchen has, a receipt or the setting
 * changes or the date does (a price can become old). Built off the main thread.
 */
fun AppContainer.observeFoodBook(): Flow<FoodBook> = combine(
    combine(recipeRepository.observeAll(), ingredientRepository.observeAll(), ingredientRepository.observeOwned()) { recipes, ingredients, owned ->
        Triple(recipes, ingredients, owned)
    },
    receiptRepository.observeSnapshot(),
    settingsStore.countOptionalExtras,
    clock.todayFlow,
) { (recipes, ingredients, owned), receipts, countOptional, today ->
    val purchases = ReceiptMapping.purchasedItems(receipts.receipts, receipts.items)
    FoodBook(
        recipes = recipes,
        ingredients = ingredients.associateBy { it.id },
        prices = Prices.resolveAll(ingredients, purchases, today),
        countOptionalExtras = countOptional,
        today = today,
        owned = owned,
    )
}.flowOn(Dispatchers.Default)
