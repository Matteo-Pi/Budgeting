package io.github.matteopi.budgeting.ui.food.diary

import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.FoodPrefs
import io.github.matteopi.budgeting.core.nutrition.Intakes
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.nutrition.MealSlots
import io.github.matteopi.budgeting.core.nutrition.Place
import io.github.matteopi.budgeting.core.nutrition.SideSuggestion
import io.github.matteopi.budgeting.core.nutrition.Sides
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import io.github.matteopi.budgeting.data.repo.BatchEaten
import io.github.matteopi.budgeting.ui.food.components.FoodBook
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the Diary, the Kitchen, the recipe page and the Food widget do the same way (docs/PLAN.md §14.5, §14.6):
 * *Ate one* with the sides last eaten with the recipe, *I cooked* a batch, and building diary rows for foods with
 * today's prices. Reached through `AppContainer.foodDiary`.
 */
class FoodDiaryActions(private val container: AppContainer) {

    suspend fun book(): FoodBook = container.observeFoodBook().first()

    suspend fun prefs(): FoodPrefs = container.settingsStore.foodPrefs.first()

    /** The meal for now. */
    fun mealNow(prefs: FoodPrefs): MealSlot = MealSlots.at(LocalTime.now(container.clock.zone()), prefs)

    /** When a row logged for [date] and [meal] was eaten: now for today, else a typical time of that meal. */
    fun eatenAt(date: LocalDate, meal: MealSlot): Long {
        val zone = container.clock.zone()
        if (date == container.clock.today()) return container.clock.nowMillis()
        val time = when (meal) {
            MealSlot.BREAKFAST -> LocalTime.of(8, 0)
            MealSlot.LUNCH -> LocalTime.of(12, 30)
            MealSlot.DINNER -> LocalTime.of(19, 0)
            MealSlot.SNACKS -> LocalTime.of(16, 0)
        }
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** A row's date, meal and time with nothing else yet. */
    fun template(date: LocalDate, meal: MealSlot): LogItem =
        LogItem(date = date, meal = meal, eatenAtEpochMillis = eatenAt(date, meal), kind = LogKind.FOOD, name = "")

    /** [amount] of [ingredient] as a diary row like [template], with its nutrition and cost now. */
    fun foodRow(template: LogItem, ingredient: Ingredient, amount: Double, price: ResolvedPrice?): LogItem {
        val eaten = Intakes.eatFood(ingredient, amount, price)
        return template.copy(
            kind = LogKind.FOOD,
            name = ingredient.name,
            amount = amount,
            amountText = Sides.describe(ingredient, amount),
            ingredientId = ingredient.id,
            recipeId = null,
            batchId = null,
            costPence = eaten.costPence,
            intake = eaten.intake,
        )
    }

    /** The price of [ingredient]: the book's, or its typed one for an ingredient the book doesn't have yet. */
    fun priceOf(ingredient: Ingredient, book: FoodBook): ResolvedPrice? =
        book.prices[ingredient.id] ?: Prices.resolve(ingredient, emptyList(), book.today)

    /** The ingredient a suggestion stands for, adding a food of the food list to the ingredients first. */
    suspend fun ingredientFor(suggestion: SideSuggestion): Ingredient = ingredientFor(suggestion.ingredient, suggestion.foodKey)

    suspend fun ingredientFor(ingredient: Ingredient, foodKey: String?): Ingredient {
        if (foodKey == null || ingredient.id != 0L) return ingredient
        val food = FoodDatabase.byKey(foodKey) ?: return ingredient
        val id = container.ingredientRepository.addFood(food)
        return container.ingredientRepository.get(id) ?: ingredient.copy(id = id)
    }

    /**
     * The sides last eaten with [recipeId] (the latest portion of it that had any), with today's numbers, as rows
     * like [template]. None when it was never eaten with sides.
     */
    suspend fun lastSides(recipeId: Long?, template: LogItem, book: FoodBook): List<LogItem> {
        if (recipeId == null) return emptyList()
        val history = container.diaryRepository.recent()
        val bySide = history.filter { it.sideOf != null }.groupBy { it.sideOf }
        val main = history.firstOrNull { it.recipeId == recipeId && it.sideOf == null && bySide[it.id].orEmpty().isNotEmpty() }
            ?: return emptyList()
        return bySide[main.id].orEmpty().mapNotNull { side ->
            val ingredient = side.ingredientId?.let { book.ingredients[it] } ?: return@mapNotNull null
            foodRow(template, ingredient, side.amount, priceOf(ingredient, book))
        }
    }

    /**
     * *Ate one*: one portion of batch [batchId] from [from] (the fridge first by default), logged for today in the
     * meal for now with the sides last eaten with its recipe. Null when it has nothing left.
     */
    suspend fun ateOne(batchId: Long, from: Place = Place.FRIDGE, withLastSides: Boolean = true, portions: Double = 1.0): AteOne? {
        val prefs = prefs()
        val today = container.clock.today()
        val template = template(today, mealNow(prefs))
        val batch = container.diaryRepository.batch(batchId) ?: return null
        val sides = if (withLastSides) lastSides(batch.recipeId, template, book()) else emptyList()
        val eaten = container.diaryRepository.eatFromBatch(batchId, portions, template, sides, from) ?: return null
        val kcal = eaten.row.intake.kcal + sides.sumOf { it.intake.kcal }
        val names = listOf(batch.name) + sides.map { shortName(it.name) }
        return AteOne(eaten, "Logged ${names.joinToString(" + ")} · ${Math.round(kcal)} kcal")
    }

    /**
     * *I cooked*: a batch of [recipe] cooked for [servings] and split into [portions], [freezer] of them frozen, made
     * on [madeOn]. Returns its id.
     */
    suspend fun cook(recipe: Recipe, servings: Double, portions: Double, freezer: Double, madeOn: LocalDate): Long {
        val book = book()
        val prefs = prefs()
        val perPortion = Intakes.portionOf(recipe, book.ingredients, book.prices, servings, portions, book.countOptionalExtras)
        val batch = Batches.make(
            name = recipe.shortTitle ?: recipe.title,
            recipeId = recipe.id,
            madeOn = madeOn,
            servings = servings,
            portions = portions,
            freezer = freezer,
            perPortion = perPortion,
            prefs = prefs,
            nowMillis = container.clock.nowMillis(),
        )
        return container.diaryRepository.addBatch(batch)
    }

    companion object {
        /** "Long-grain white rice" -> "rice": the last word, for the short "Logged Chilli + rice". */
        fun shortName(name: String): String {
            val plain = name.replace(Regex("""\([^)]*\)"""), " ").trim()
            return plain.split(Regex("""\s+""")).lastOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: name
        }
    }
}

/** What *Ate one* did, and the message to show. */
data class AteOne(val eaten: BatchEaten, val message: String)
