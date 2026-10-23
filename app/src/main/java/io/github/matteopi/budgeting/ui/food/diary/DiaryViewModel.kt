package io.github.matteopi.budgeting.ui.food.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.DaySummary
import io.github.matteopi.budgeting.core.nutrition.DiaryMaths
import io.github.matteopi.budgeting.core.nutrition.FoodPrefs
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.nutrition.Sides
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Quantities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One row of a meal: a portion, a food or a quick entry, or a side indented under its portion. */
data class DiaryRow(val item: LogItem, val isSide: Boolean)

data class MealSection(val meal: MealSlot, val kcal: Double, val rows: List<DiaryRow>)

data class DiaryUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val summary: DaySummary = DiaryMaths.day(emptyList()),
    val prefs: FoodPrefs = FoodPrefs(),
    val meals: List<MealSection> = MealSlot.entries.map { MealSection(it, 0.0, emptyList()) },
    /** The kitchen's batches with portions left, most pressing first. */
    val ready: List<Batch> = emptyList(),
    val ingredients: Map<Long, Ingredient> = emptyMap(),
) {
    val title: String get() = dayTitle(date, today)
}

/** A message for the snackbar, with an Undo when [undo] is set. */
data class DiaryMessage(val text: String, val undo: (() -> Unit)? = null)

/**
 * The Diary tab (docs/PLAN.md §14.5): one day's meals and their rows, the summary against the targets, water, and
 * the kitchen's batches ready to eat. Deleting a row and *Ate one* can be undone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(private val container: AppContainer) : ViewModel() {

    private val dateFlow = MutableStateFlow(container.clock.today())
    private val _message = MutableStateFlow<DiaryMessage?>(null)
    val message: StateFlow<DiaryMessage?> = _message.asStateFlow()

    private val dayFlow = dateFlow.flatMapLatest { date ->
        combine(container.diaryRepository.observeDay(date), container.diaryRepository.observeFoodDay(date)) { items, day ->
            Triple(date, items, day)
        }
    }

    val uiState: StateFlow<DiaryUiState> = combine(
        dayFlow,
        container.diaryRepository.observeBatches(),
        container.settingsStore.foodPrefs,
        container.ingredientRepository.observeAll(),
        container.clock.todayFlow,
    ) { (date, items, day), batches, prefs, ingredients, today ->
        DiaryUiState(
            loading = false,
            date = date,
            today = today,
            summary = DiaryMaths.day(items, day.waterMl, prefs.targets),
            prefs = prefs,
            meals = meals(items),
            ready = Batches.ready(batches),
            ingredients = ingredients.associateBy { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    fun setDate(date: LocalDate) {
        dateFlow.value = date
    }

    fun previousDay() = setDate(dateFlow.value.minusDays(1))

    fun nextDay() = setDate(dateFlow.value.plusDays(1))

    fun addGlass() {
        val date = dateFlow.value
        viewModelScope.launch { container.diaryRepository.addWater(date, uiState.value.prefs.glassMl) }
    }

    fun removeGlass() {
        val date = dateFlow.value
        viewModelScope.launch { container.diaryRepository.addWater(date, -uiState.value.prefs.glassMl) }
    }

    /** *Ate one* from the *Ready to eat* list: today, the meal for now, with the sides last eaten with it. */
    fun ateOne(batchId: Long) {
        viewModelScope.launch {
            val done = container.foodDiary.ateOne(batchId)
            _message.value = if (done == null) {
                DiaryMessage("None left to eat")
            } else {
                DiaryMessage(done.message) { viewModelScope.launch { container.diaryRepository.undoEat(done.eaten) } }
            }
        }
    }

    fun delete(row: LogItem) {
        viewModelScope.launch {
            val deleted = container.diaryRepository.delete(row.id) ?: return@launch
            val back = if (row.kind == LogKind.BATCH && row.sideOf == null) " · the portion went back to the kitchen" else ""
            _message.value = DiaryMessage("Deleted ${row.name}$back") {
                viewModelScope.launch { container.diaryRepository.restore(deleted) }
            }
        }
    }

    /** Saves [row] with [amount] (its numbers scaled to it) in [meal]. */
    fun update(row: LogItem, amount: Double, meal: MealSlot) {
        val ingredient = row.ingredientId?.let { uiState.value.ingredients[it] }
        viewModelScope.launch { container.diaryRepository.update(DiaryMapping.withAmount(row, amount, ingredient).copy(meal = meal)) }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        /** The day's rows by meal, each portion followed by its sides. */
        fun meals(items: List<LogItem>): List<MealSection> {
            val ordered = DiaryMaths.ordered(items)
            return MealSlot.entries.map { meal ->
                val rows = ordered.filter { it.meal == meal || (it.sideOf != null && ordered.firstOrNull { main -> main.id == it.sideOf }?.meal == meal) }
                    .distinctBy { it.id }
                MealSection(meal, rows.sumOf { it.intake.kcal }, rows.map { DiaryRow(it, it.sideOf != null && items.any { main -> main.id == it.sideOf }) })
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                DiaryViewModel(app.container)
            }
        }
    }
}

/** Pure helpers of the diary's rows, tested on the JVM. */
object DiaryMapping {
    /**
     * [row] with a new [amount]: its nutrition and cost scaled to it (they were frozen when it was logged), and its
     * text redone ("3 slices", "1½ portions").
     */
    fun withAmount(row: LogItem, amount: Double, ingredient: Ingredient?): LogItem {
        if (!(amount > 0) || !(row.amount > 0)) return row
        val factor = amount / row.amount
        val text = when (row.kind) {
            LogKind.BATCH, LogKind.RECIPE -> Batches.portionsText(amount)
            LogKind.FOOD -> ingredient?.let { Sides.describe(it, amount) } ?: Quantities.format(amount, io.github.matteopi.budgeting.core.recipes.BaseUnit.G)
            LogKind.QUICK -> if (Math.abs(amount - 1.0) < 1e-9) "" else "× ${countText(amount)}"
        }
        return row.copy(amount = amount, amountText = text, intake = row.intake * factor, costPence = row.costPence?.let { it * factor })
    }

    /** What a row shows under its name: "1 portion · 41 g protein". */
    fun detail(row: LogItem): String =
        listOf(row.amountText, "${gramsText(row.intake.protein)} protein").filter { it.isNotBlank() }.joinToString(" · ")

    /** "3 portions in the fridge, 2 in the freezer". */
    fun leftText(batch: Batch): String {
        val parts = mutableListOf<String>()
        if (batch.fridgeLeft > Batches.EPSILON) parts += "${countText(batch.fridgeLeft)} in the fridge"
        if (batch.freezerLeft > Batches.EPSILON) parts += "${countText(batch.freezerLeft)} in the freezer"
        return parts.joinToString(", ").ifEmpty { "None left" }
    }
}
