package io.github.matteopi.budgeting.ui.food.ingredient

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.Food
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.LabelReading
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.OffDraft
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.data.repo.IngredientRepository
import io.github.matteopi.budgeting.data.repo.ReceiptRepository
import io.github.matteopi.budgeting.data.repo.RecipeRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A recipe that uses the ingredient. */
data class UsedInRecipe(val id: Long, val title: String)

/** A product from the receipts, for the link picker; [lastPaid] is "last paid £0.89 at Tesco 3 Nov" or null if never bought. */
data class ProductChoice(val id: Long, val name: String, val lastPaid: String?)

enum class EditPhase { LOADING, READY, MISSING }

/**
 * Drives the ingredient editor (docs/PLAN.md §11.5): the fields as typed in [form], checked by
 * [IngredientFormRules] when saved, the product link picker over the receipts' products, and where the
 * ingredient is used. Typing goes straight into a plain `MutableStateFlow` so it is never delayed.
 */
class IngredientEditViewModel(
    private val ingredientId: Long?,
    /** A barcode scanned on the Ingredients tab: a new ingredient starts with it filled in. */
    startBarcode: String? = null,
    private val ingredientRepository: IngredientRepository,
    recipeRepository: RecipeRepository,
    receiptRepository: ReceiptRepository,
    private val clock: Clock,
) : ViewModel() {

    private val mutablePhase = MutableStateFlow(if (ingredientId == null) EditPhase.READY else EditPhase.LOADING)
    val phase: StateFlow<EditPhase> = mutablePhase.asStateFlow()

    private val mutableForm = MutableStateFlow(IngredientForm())
    val form: StateFlow<IngredientForm> = mutableForm.asStateFlow()

    private val mutableSubmitted = MutableStateFlow(false)

    /** True once Save has been tried, which is when fields that are wrong start showing in red. */
    val submitted: StateFlow<Boolean> = mutableSubmitted.asStateFlow()

    private val mutableSaving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = mutableSaving.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    private var base: Ingredient? = null

    /** Today, for the price date field. */
    val today: LocalDate get() = clock.today()

    /** The receipts' products by name, each with the latest price paid for it. */
    val products: StateFlow<List<ProductChoice>> = combine(
        receiptRepository.observeProducts(),
        receiptRepository.observeReceipts(),
        receiptRepository.observeAllItems(),
    ) { products, receipts, items ->
        val purchases = ReceiptMapping.purchasedItems(receipts, items)
        val now = clock.today()
        products.sortedBy { it.name.lowercase() }.map { product ->
            ProductChoice(
                id = product.id,
                name = product.name,
                lastPaid = FoodTexts.latestPaid(purchases, product.id)?.let { FoodTexts.lastPaidText(it, now) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The food of the food list that fits the name, while no nutrition has been typed in (docs/PLAN.md §11.12). */
    val foodMatch: StateFlow<Food?> = mutableForm
        .map { form -> if (ScanRules.hasNoNutrition(form)) FoodDatabase.match(form.name) else null }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The non-archived recipes that have a line for this ingredient. */
    val usedIn: StateFlow<List<UsedInRecipe>> = recipeRepository.observeAll().map { recipes ->
        val id = ingredientId
        if (id == null) {
            emptyList()
        } else {
            recipes
                .filter { recipe -> !recipe.archived && recipe.lines.any { it.ingredientId == id } }
                .map { UsedInRecipe(it.id, it.title) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (ingredientId == null && startBarcode != null) mutableForm.value = IngredientForm(barcode = startBarcode)
        if (ingredientId != null) {
            viewModelScope.launch {
                val ingredient = ingredientRepository.get(ingredientId)
                if (ingredient == null) {
                    mutablePhase.value = EditPhase.MISSING
                } else {
                    base = ingredient
                    mutableForm.value = IngredientFormRules.fromIngredient(ingredient)
                    mutablePhase.value = EditPhase.READY
                }
            }
        }
    }

    // ---- editing --------------------------------------------------------------------------

    private fun change(edit: (IngredientForm) -> IngredientForm) {
        mutableForm.update(edit)
    }

    fun setName(text: String) = change { it.copy(name = text) }

    fun setUnit(unit: BaseUnit) = change { it.copy(unit = unit) }

    fun setPackSize(text: String) = change { it.copy(packSizeText = text) }

    fun setPackLabel(text: String) = change { it.copy(packLabel = text) }

    /** A new or changed price counts as checked today, unless the date was picked by hand. */
    fun setPrice(text: String) = change { form ->
        val changed = text.isNotBlank() && text != form.priceText && !form.priceDateTouched
        form.copy(priceText = text, priceDate = if (changed) clock.today() else form.priceDate)
    }

    fun setPriceDate(date: LocalDate) = change { it.copy(priceDate = date, priceDateTouched = true) }

    fun setStore(text: String) = change { it.copy(store = text) }

    fun setProductName(text: String) = change { it.copy(productName = text) }

    fun setSourceUrl(text: String) = change { it.copy(sourceUrl = text) }

    /** Only digits are kept. */
    fun setBarcode(text: String) = change { it.copy(barcode = IngredientFormRules.digitsOnly(text)) }

    fun setNutrition(nutrient: Nutrient, text: String) = change { it.copy(nutrition = it.nutrition + (nutrient to text)) }

    fun setNutritionSource(source: NutritionSource) = change { it.copy(nutritionSource = source) }

    /** The values read from a label photo (checked on the review dialog first). */
    fun applyLabel(reading: LabelReading) = change { ScanRules.applyLabel(it, reading) }

    /** What Open Food Facts gave for the scanned barcode (checked on the review dialog first). */
    fun applyDraft(draft: OffDraft) = change { ScanRules.applyDraft(it, draft) }

    /** *Fill in from the food list*: the empty fields from [food], and its price if there is none. */
    fun fillFromFood(food: Food) = change { ScanRules.applyFood(it, food) }

    fun setVegKind(kind: VegKind) = change { it.copy(vegKind = kind) }

    fun setShelf(shelf: Shelf) = change { it.copy(shelf = shelf) }

    /** *Counts as*: null to guess from the name, else the person's choice (`NONE` for neither). */
    fun setFoodGroup(group: FoodGroup?) = change { it.copy(foodGroup = group) }

    fun setNote(text: String) = change { it.copy(note = text) }

    fun addPortion() = change { it.copy(portions = it.portions + PortionRow(it.nextKey), nextKey = it.nextKey + 1) }

    fun setPortionName(key: Long, text: String) =
        change { form -> form.copy(portions = form.portions.map { if (it.key == key) it.copy(name = text) else it }) }

    fun setPortionAmount(key: Long, text: String) =
        change { form -> form.copy(portions = form.portions.map { if (it.key == key) it.copy(amountText = text) else it }) }

    fun removePortion(key: Long) = change { form -> form.copy(portions = form.portions.filter { it.key != key }) }

    /** Links the ingredient to a receipts product (its price then comes from what was paid), or unlinks it with null. */
    fun linkProduct(productId: Long?) = change { it.copy(productId = productId) }

    // ---- saving ---------------------------------------------------------------------------

    /** Saves the ingredient and calls [onSaved]; shows what is wrong instead when a field is not valid. */
    fun save(onSaved: () -> Unit) {
        if (mutableSaving.value) return
        mutableSubmitted.value = true
        val ingredient = IngredientFormRules.build(mutableForm.value, base, clock.today())
        if (ingredient == null) {
            mutableError.value = IngredientFormRules.errors(mutableForm.value).message
            return
        }
        mutableSaving.value = true
        mutableError.value = null
        viewModelScope.launch {
            try {
                ingredientRepository.save(ingredient)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLiteConstraintException) {
                mutableError.value = "Another ingredient already has that barcode."
            } catch (e: Exception) {
                mutableError.value = "Couldn't save the ingredient: ${e.message ?: "unknown error"}"
            } finally {
                mutableSaving.value = false
            }
        }
    }

    /** Hides the ingredient from pickers and matching. Recipes that use it keep it. */
    fun archive(onDone: () -> Unit) {
        val id = ingredientId ?: return
        viewModelScope.launch {
            try {
                ingredientRepository.setArchived(id, true)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableError.value = "Couldn't archive the ingredient: ${e.message ?: "unknown error"}"
            }
        }
    }

    companion object {
        fun factory(ingredientId: Long?, barcode: String? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                val container = app.container
                IngredientEditViewModel(
                    ingredientId = ingredientId,
                    startBarcode = barcode,
                    ingredientRepository = container.ingredientRepository,
                    recipeRepository = container.recipeRepository,
                    receiptRepository = container.receiptRepository,
                    clock = container.clock,
                )
            }
        }
    }
}
