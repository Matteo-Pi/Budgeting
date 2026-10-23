package io.github.matteopi.budgeting.ui.food.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.Eaten
import io.github.matteopi.budgeting.core.nutrition.Intake
import io.github.matteopi.budgeting.core.nutrition.Intakes
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.nutrition.MealSlots
import io.github.matteopi.budgeting.core.nutrition.QuickFood
import io.github.matteopi.budgeting.core.nutrition.QuickFoodResult
import io.github.matteopi.budgeting.core.nutrition.SideSuggestion
import io.github.matteopi.budgeting.core.nutrition.Sides
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientMatching
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.ui.food.components.FoodBook
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** The tabs of *Log food*. */
enum class LogTab(val label: String) { KITCHEN("Kitchen"), RECIPES("Recipes"), FOODS("Foods"), QUICK("Quick") }

/** What is being logged. */
sealed interface Pick {
    data class FromBatch(val batch: Batch) : Pick
    data class FromRecipe(val recipe: Recipe) : Pick
    /** One of the user's ingredients, or a food of the food list ([foodKey] set, not added yet). */
    data class FromFood(val ingredient: Ingredient, val foodKey: String?) : Pick
}

/** A food the lists offer: [key] is `i<id>` for an ingredient or `f<key>` for a food of the list. */
data class FoodOption(val key: String, val ingredient: Ingredient, val foodKey: String?, val detail: String)

/** A side on the portion, with its amount in base units. */
data class SideChoice(val key: String, val ingredient: Ingredient, val foodKey: String?, val amount: Double)

/** The choices made so far. */
data class LogForm(
    val date: LocalDate,
    val meal: MealSlot,
    val tab: LogTab = LogTab.KITCHEN,
    val query: String = "",
    val pick: Pick? = null,
    val portions: Double = 1.0,
    val amount: Double = 100.0,
    val sides: List<SideChoice> = emptyList(),
    val quickText: String = "",
    val quickName: String = "",
    val quickKcal: String = "",
    val quickProtein: String = "",
    val quickCarbs: String = "",
    val quickFat: String = "",
    val saving: Boolean = false,
    val sideSearch: String? = null,
)

data class LogFoodUiState(
    val loading: Boolean = true,
    val form: LogForm = LogForm(LocalDate.now(), MealSlot.LUNCH),
    val today: LocalDate = LocalDate.now(),
    val batches: List<Batch> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val foods: List<FoodOption> = emptyList(),
    val sideFoods: List<FoodOption> = emptyList(),
    val suggestions: List<SideSuggestion> = emptyList(),
    val quick: QuickFoodResult? = null,
    /** What is being logged, sides included. */
    val total: Eaten = Eaten(Intake.NONE, null),
    val canSave: Boolean = false,
)

/**
 * *Log food* (docs/PLAN.md §14.5): a portion from the kitchen, a portion of a recipe, a food by amount, or a quick
 * entry, with sides on a portion and live totals. Foods of the food list are added to the ingredients when logged.
 */
class LogFoodViewModel(
    private val container: AppContainer,
    epochDay: Long?,
    meal: String?,
    private val batchId: Long?,
    private val recipeId: Long?,
) : ViewModel() {

    private val startDate = epochDay?.let { LocalDate.ofEpochDay(it) } ?: container.clock.today()
    private val form = MutableStateFlow(
        LogForm(
            date = startDate,
            meal = MealSlot.entries.firstOrNull { it.name == meal } ?: MealSlots.at(LocalTime.now(container.clock.zone())),
            tab = if (recipeId != null) LogTab.RECIPES else LogTab.KITCHEN,
        ),
    )

    val uiState: StateFlow<LogFoodUiState> = combine(
        form,
        container.observeFoodBook(),
        container.diaryRepository.observeBatches(),
        container.diaryRepository.observeRecent(),
        container.ingredientRepository.observeAliasMap(),
    ) { f, book, batches, history, aliases ->
        state(f, book, Batches.ready(batches), history, aliases)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogFoodUiState(form = form.value))

    init {
        viewModelScope.launch {
            val prefs = container.settingsStore.foodPrefs.first()
            if (meal == null && startDate == container.clock.today()) form.update { it.copy(meal = MealSlots.at(LocalTime.now(container.clock.zone()), prefs)) }
            val ready = Batches.ready(container.diaryRepository.observeBatches().first())
            if (ready.isEmpty() && batchId == null && recipeId == null) form.update { it.copy(tab = LogTab.FOODS) }
            batchId?.let { id -> container.diaryRepository.batch(id)?.let { pickBatch(it) } }
            recipeId?.let { id -> container.observeFoodBook().first().recipes.firstOrNull { it.id == id }?.let { pickRecipe(it) } }
        }
    }

    private fun state(f: LogForm, book: FoodBook, ready: List<Batch>, history: List<LogItem>, aliases: Map<String, Long>): LogFoodUiState {
        val live = book.ingredients.values.filter { !it.archived }
        val recentRecipeIds = history.mapNotNull { it.recipeId }.distinct()
        val recipes = book.liveRecipes.sortedWith(
            compareBy<Recipe> { recentRecipeIds.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.title.lowercase() },
        ).filter { f.query.isBlank() || it.title.contains(f.query.trim(), ignoreCase = true) }
        val quick = if (f.tab == LogTab.QUICK && f.quickText.isNotBlank()) QuickFood.read(f.quickText, live, aliases) else null
        val pickRecipeId = when (val pick = f.pick) {
            is Pick.FromBatch -> pick.batch.recipeId
            is Pick.FromRecipe -> pick.recipe.id
            else -> null
        }
        val suggestions = if (pickRecipeId != null || f.pick is Pick.FromBatch) {
            Sides.suggestions(book.recipes.firstOrNull { it.id == pickRecipeId }, live, history)
                .filter { suggestion -> f.sides.none { it.key == keyOf(suggestion.ingredient, suggestion.foodKey) } }
        } else {
            emptyList()
        }
        val total = total(f, book)
        return LogFoodUiState(
            loading = false,
            form = f,
            today = container.clock.today(),
            batches = ready,
            recipes = recipes,
            foods = foods(f.query, live, history),
            sideFoods = f.sideSearch?.let { foods(it, live, history).take(20) } ?: emptyList(),
            suggestions = suggestions,
            quick = quick,
            total = total,
            canSave = !f.saving && (f.pick != null || (f.tab == LogTab.QUICK && quickKcal(f) != null)),
        )
    }

    /** The user's ingredients and the food list's foods for [query]: recent ones first when it is blank. */
    private fun foods(query: String, live: List<Ingredient>, history: List<LogItem>): List<FoodOption> {
        val q = query.trim()
        fun option(ingredient: Ingredient) = FoodOption(keyOf(ingredient, null), ingredient, null, detailOf(ingredient))
        if (q.isEmpty()) {
            val recentIds = history.filter { it.kind == LogKind.FOOD }.mapNotNull { it.ingredientId }.distinct()
            val byId = live.associateBy { it.id }
            val recent = recentIds.mapNotNull { byId[it] }
            return (recent + live.sortedBy { it.name.lowercase() }).distinctBy { it.id }.take(MAX_FOODS).map(::option)
        }
        val mine = live.filter { it.name.contains(q, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        val names = mine.map { IngredientMatching.nameKey(it.name) }.toSet()
        val listed = FoodDatabase.search(q).filter { IngredientMatching.nameKey(it.name) !in names && live.none { i -> i.name.equals(it.name, true) } }
            .take(MAX_FOODS)
            .map { FoodOption(keyOf(it.ingredient, it.key), it.ingredient, it.key, "From the food list · ${detailOf(it.ingredient)}") }
        return mine.take(MAX_FOODS).map(::option) + listed
    }

    private fun detailOf(ingredient: Ingredient): String {
        val amount = Sides.defaultAmount(ingredient)
        val kcal = ingredient.per100.kcal?.let { "${Quantities.roundHalfUp(it * amount.amount / 100)} kcal" } ?: "kcal unknown"
        return "${amount.text} · $kcal"
    }

    private fun total(f: LogForm, book: FoodBook): Eaten {
        val actions = container.foodDiary
        val main: Eaten? = when (val pick = f.pick) {
            is Pick.FromBatch -> Eaten(pick.batch.intake * f.portions, pick.batch.costPence?.let { it * f.portions })
            is Pick.FromRecipe -> Intakes.portionOf(pick.recipe, book.ingredients, book.prices, countOptional = book.countOptionalExtras).let {
                Eaten(it.intake * f.portions, it.costPence?.let { c -> c * f.portions }, it.unpriced)
            }
            is Pick.FromFood -> Intakes.eatFood(pick.ingredient, f.amount, actions.priceOf(pick.ingredient, book))
            null -> quickKcal(f)?.let { Eaten(quickIntake(f, it), null) }
        }
        val sides = if (f.pick is Pick.FromBatch || f.pick is Pick.FromRecipe) {
            f.sides.map { Intakes.eatFood(it.ingredient, it.amount, actions.priceOf(it.ingredient, book)) }
        } else {
            emptyList()
        }
        val all = listOfNotNull(main) + sides
        val priced = all.mapNotNull { it.costPence }
        return Eaten(Intake.sum(all.map { it.intake }), if (priced.isEmpty()) null else priced.sum(), all.count { it.costPence == null })
    }

    private fun number(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it >= 0 }

    private fun quickKcal(f: LogForm): Double? = number(f.quickKcal)?.takeIf { it > 0 }

    private fun quickIntake(f: LogForm, kcal: Double): Intake =
        Intakes.quick(kcal, protein = number(f.quickProtein), carbs = number(f.quickCarbs), fat = number(f.quickFat))

    // ---- Choices --------------------------------------------------------------------------------

    fun setTab(tab: LogTab) = form.update { it.copy(tab = tab, query = "") }
    fun setQuery(query: String) = form.update { it.copy(query = query) }
    fun setMeal(meal: MealSlot) = form.update { it.copy(meal = meal) }
    fun setPortions(portions: Double) = form.update { it.copy(portions = portions.coerceIn(0.5, 20.0)) }
    fun setAmount(amount: Double) = form.update { it.copy(amount = amount.coerceIn(1.0, 10_000.0)) }
    fun setQuickText(text: String) = form.update { it.copy(quickText = text) }
    fun setQuickField(name: String? = null, kcal: String? = null, protein: String? = null, carbs: String? = null, fat: String? = null) =
        form.update {
            it.copy(
                quickName = name ?: it.quickName,
                quickKcal = kcal ?: it.quickKcal,
                quickProtein = protein ?: it.quickProtein,
                quickCarbs = carbs ?: it.quickCarbs,
                quickFat = fat ?: it.quickFat,
            )
        }

    fun clearPick() = form.update { it.copy(pick = null, sides = emptyList()) }

    fun pickBatch(batch: Batch) {
        form.update { it.copy(pick = Pick.FromBatch(batch), portions = 1.0, sides = emptyList()) }
        prefillSides(batch.recipeId)
    }

    fun pickRecipe(recipe: Recipe) {
        form.update { it.copy(pick = Pick.FromRecipe(recipe), portions = 1.0, sides = emptyList()) }
        prefillSides(recipe.id)
    }

    fun pickFood(option: FoodOption, amount: Double? = null) {
        form.update {
            it.copy(pick = Pick.FromFood(option.ingredient, option.foodKey), amount = amount ?: Sides.defaultAmount(option.ingredient).amount, sides = emptyList())
        }
    }

    /** *Use this* on what the quick box read. */
    fun useQuick(result: QuickFoodResult) {
        pickFood(FoodOption(keyOf(result.ingredient, result.foodKey), result.ingredient, result.foodKey, ""), result.amount.amount)
    }

    /** The sides last eaten with the recipe, as a start. */
    private fun prefillSides(recipeId: Long?) {
        if (recipeId == null) return
        viewModelScope.launch {
            val book = container.observeFoodBook().first()
            val template = container.foodDiary.template(form.value.date, form.value.meal)
            val last = container.foodDiary.lastSides(recipeId, template, book)
            val choices = last.mapNotNull { row ->
                val ingredient = row.ingredientId?.let { book.ingredients[it] } ?: return@mapNotNull null
                SideChoice(keyOf(ingredient, null), ingredient, null, row.amount)
            }
            if (choices.isNotEmpty()) form.update { it.copy(sides = choices) }
        }
    }

    fun addSide(suggestion: SideSuggestion) = addSide(suggestion.ingredient, suggestion.foodKey, suggestion.amount.amount)

    fun addSide(option: FoodOption) = addSide(option.ingredient, option.foodKey, Sides.defaultAmount(option.ingredient).amount)

    private fun addSide(ingredient: Ingredient, foodKey: String?, amount: Double) {
        val key = keyOf(ingredient, foodKey)
        form.update { f -> if (f.sides.any { it.key == key }) f.copy(sideSearch = null) else f.copy(sides = f.sides + SideChoice(key, ingredient, foodKey, amount), sideSearch = null) }
    }

    fun setSideAmount(key: String, amount: Double) =
        form.update { f -> f.copy(sides = f.sides.map { if (it.key == key) it.copy(amount = amount.coerceAtLeast(1.0)) else it }) }

    fun removeSide(key: String) = form.update { f -> f.copy(sides = f.sides.filter { it.key != key }) }

    fun openSideSearch() = form.update { it.copy(sideSearch = "") }
    fun setSideSearch(text: String) = form.update { it.copy(sideSearch = text) }
    fun closeSideSearch() = form.update { it.copy(sideSearch = null) }

    /** Saves what is being logged, then [onDone] with a short message. */
    fun save(onDone: (String) -> Unit) {
        val f = form.value
        if (f.saving) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val message = runCatching { saveNow(f) }.getOrElse { "Couldn't log it: ${it.message}" }
            form.update { it.copy(saving = false) }
            if (message != null) onDone(message)
        }
    }

    private suspend fun saveNow(f: LogForm): String? {
        val actions = container.foodDiary
        val book = container.observeFoodBook().first()
        val template = actions.template(f.date, f.meal)
        suspend fun sideRows(): List<LogItem> = f.sides.map { side ->
            val ingredient = actions.ingredientFor(side.ingredient, side.foodKey)
            val current = container.observeFoodBook().first()
            actions.foodRow(template, ingredient, side.amount, actions.priceOf(ingredient, current))
        }
        return when (val pick = f.pick) {
            is Pick.FromBatch -> {
                val sides = sideRows()
                container.diaryRepository.eatFromBatch(pick.batch.id, f.portions, template, sides) ?: return "None of ${pick.batch.name} is left"
                "Logged ${pick.batch.name}"
            }
            is Pick.FromRecipe -> {
                val sides = sideRows()
                val eaten = Intakes.portionOf(pick.recipe, book.ingredients, book.prices, countOptional = book.countOptionalExtras)
                val main = template.copy(
                    kind = LogKind.RECIPE,
                    name = pick.recipe.shortTitle ?: pick.recipe.title,
                    amount = f.portions,
                    amountText = Batches.portionsText(f.portions),
                    recipeId = pick.recipe.id,
                    costPence = eaten.costPence?.let { it * f.portions },
                    intake = eaten.intake * f.portions,
                )
                container.diaryRepository.log(main, sides)
                "Logged ${main.name}"
            }
            is Pick.FromFood -> {
                val ingredient = actions.ingredientFor(pick.ingredient, pick.foodKey)
                val current = container.observeFoodBook().first()
                val row = actions.foodRow(template, ingredient, f.amount, actions.priceOf(ingredient, current))
                container.diaryRepository.log(row)
                "Logged ${row.name}"
            }
            null -> {
                val kcal = quickKcal(f) ?: return null
                val name = f.quickName.trim().ifEmpty { "Quick entry" }
                container.diaryRepository.log(template.copy(kind = LogKind.QUICK, name = name, amount = 1.0, amountText = "", intake = quickIntake(f, kcal)))
                "Logged $name"
            }
        }
    }

    companion object {
        private const val MAX_FOODS = 40

        fun keyOf(ingredient: Ingredient, foodKey: String?): String = if (foodKey != null && ingredient.id == 0L) "f$foodKey" else "i${ingredient.id}"

        /** The step a side's or a food's stepper moves by: half of one of its portions (not a spoon), else 25 g. */
        fun stepOf(ingredient: Ingredient): Double =
            ingredient.portions.firstOrNull { it.amount > 0 && Quantities.stem(it.name) !in SPOONS }?.amount?.div(2) ?: 25.0

        private val SPOONS = setOf("tsp", "tbsp", "pinch", "spray", "handful").map { Quantities.stem(it) }.toSet()

        fun factory(epochDay: Long?, meal: String?, batchId: Long?, recipeId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                LogFoodViewModel(app.container, epochDay, meal, batchId, recipeId)
            }
        }
    }
}
