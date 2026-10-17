package io.github.matteopi.budgeting.ui.food.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientLines
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.data.repo.IngredientRepository
import io.github.matteopi.budgeting.data.repo.ReceiptRepository
import io.github.matteopi.budgeting.data.repo.RecipeRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.food.ingredient.IngredientFormRules
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which step of the add/edit flow is showing. */
enum class ReviewPhase { LOADING, PASTE, REVIEW, MISSING }

/** The short inline form that creates an ingredient for a line (docs/PLAN.md §11.4). */
data class CreateIngredientForm(
    val lineKey: Long,
    val name: String,
    val unit: BaseUnit = BaseUnit.G,
    val packSizeText: String = "",
    val packLabel: String = "",
    val priceText: String = "",
    val shelf: Shelf = Shelf.OTHER,
    val vegKind: VegKind = VegKind.NONE,
    val error: String? = null,
)

/**
 * Drives adding a recipe (a paste step, then the review) and editing one (the review, prefilled)
 * (docs/PLAN.md §11.4, §11.8).
 *
 * The raw fields are in [edit], a plain `MutableStateFlow` so typing is never delayed; everything worked
 * out from them, the ingredients and the prices is in [derived]. [recipeId] is the recipe being edited, null
 * when adding. [textFile] is a cache file holding shared text, read when the screen opens and then deleted.
 */
class RecipeReviewViewModel(
    private val ingredientRepository: IngredientRepository,
    private val recipeRepository: RecipeRepository,
    receiptRepository: ReceiptRepository,
    settingsStore: SettingsStore,
    private val clock: Clock,
    private val recipeId: Long?,
    private val textFile: File?,
) : ViewModel() {

    private val mutablePhase = MutableStateFlow(ReviewPhase.LOADING)
    val phase: StateFlow<ReviewPhase> = mutablePhase.asStateFlow()

    private val mutablePasteText = MutableStateFlow("")
    val pasteText: StateFlow<String> = mutablePasteText.asStateFlow()

    private val mutableEdit = MutableStateFlow(ReviewEdit())
    val edit: StateFlow<ReviewEdit> = mutableEdit.asStateFlow()

    private val mutableWarnings = MutableStateFlow<List<String>>(emptyList())

    /** What the paste format could not understand (`RecipeDraft.warnings`), shown above the review. */
    val warnings: StateFlow<List<String>> = mutableWarnings.asStateFlow()

    private val mutableCreate = MutableStateFlow<CreateIngredientForm?>(null)

    /** The open "create ingredient" form, if any: it belongs to one line. */
    val create: StateFlow<CreateIngredientForm?> = mutableCreate.asStateFlow()

    private val mutableSaving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = mutableSaving.asStateFlow()

    private val mutableSaveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = mutableSaveError.asStateFlow()

    /** The recipe being edited, whose id, short title, rating, notes, source and dates are kept. */
    private var base: Recipe? = null

    /** The edit as the review first showed it, to tell whether anything has changed. */
    private var initialEdit = ReviewEdit()

    /** Every ingredient, archived ones too. */
    val ingredients: StateFlow<List<Ingredient>> = ingredientRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Learned names for `IngredientMatching.match`. */
    private val aliases: StateFlow<Map<String, Long>> = ingredientRepository.observeAliasMap()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Prices as every screen works them out: a price paid on a receipt wins once an ingredient is linked to a product. */
    private val prices: StateFlow<Map<Long, ResolvedPrice>> = combine(
        ingredientRepository.observeAll(),
        receiptRepository.observeReceipts(),
        receiptRepository.observeAllItems(),
    ) { all, receipts, items ->
        Prices.resolveAll(all, ReceiptMapping.purchasedItems(receipts, items), clock.today())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val countOptional: StateFlow<Boolean> = settingsStore.countOptionalExtras
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val ingredientsById: Flow<Map<Long, Ingredient>> = ingredients.map { list -> list.associateBy { it.id } }

    val derived: StateFlow<ReviewDerived?> = combine(mutableEdit, ingredientsById, prices, countOptional) { edit, byId, priced, optional ->
        ReviewRules.derive(edit, base, byId, priced, optional)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (recipeId != null) {
            val recipe = recipeRepository.get(recipeId)
            if (recipe == null) {
                mutablePhase.value = ReviewPhase.MISSING
                return
            }
            base = recipe
            val first = ReviewRules.fromRecipe(recipe, ingredientRepository.observeAll().first(), ingredientRepository.aliasMap())
            initialEdit = first
            mutableEdit.value = first
            mutablePhase.value = ReviewPhase.REVIEW
            return
        }
        val file = textFile
        if (file != null) {
            // Shared text: read it once, then the file has done its job.
            val text = withContext(Dispatchers.IO) {
                try {
                    file.readText().also { file.delete() }
                } catch (e: Exception) {
                    null
                }
            }
            if (!text.isNullOrBlank()) {
                mutablePasteText.value = text
                review(text)
                return
            }
        }
        mutablePhase.value = ReviewPhase.PASTE
    }

    // ---- the paste step -----------------------------------------------------------------------

    fun setPasteText(text: String) {
        mutablePasteText.value = text
    }

    /** *Read recipe*: reads the pasted text into the review. */
    fun readRecipe() {
        val text = mutablePasteText.value
        if (text.isBlank()) return
        viewModelScope.launch { review(text) }
    }

    /** *Type it instead*: an empty review. */
    fun typeInstead() {
        initialEdit = ReviewEdit()
        mutableEdit.value = initialEdit
        mutableWarnings.value = emptyList()
        mutablePhase.value = ReviewPhase.REVIEW
    }

    private suspend fun review(text: String) {
        val draft = RecipeText.parse(text)
        val first = ReviewRules.fromDraft(draft, ingredientRepository.observeAll().first(), ingredientRepository.aliasMap())
        initialEdit = first
        mutableEdit.value = first
        mutableWarnings.value = draft.warnings
        mutablePhase.value = ReviewPhase.REVIEW
    }

    /** True when the review has been changed since it opened, so leaving it should ask first. */
    fun isDirty(current: ReviewEdit): Boolean = current != initialEdit

    // ---- the header -------------------------------------------------------------------------------

    fun setTitle(text: String) = mutableEdit.update { it.copy(title = text) }

    fun setServes(text: String) = mutableEdit.update { it.copy(servesText = text) }

    fun setPrep(text: String) = mutableEdit.update { it.copy(prepText = text) }

    fun setCook(text: String) = mutableEdit.update { it.copy(cookText = text) }

    fun setStyle(text: String) = mutableEdit.update { it.copy(style = text) }

    fun setTags(text: String) = mutableEdit.update { it.copy(tagsText = text) }

    fun setAbout(text: String) = mutableEdit.update { it.copy(about = text) }

    fun setServedWith(text: String) = mutableEdit.update { it.copy(servedWith = text) }

    fun setTimeNote(text: String) = mutableEdit.update { it.copy(timeNote = text) }

    // ---- the lines --------------------------------------------------------------------------------

    private fun updateLine(key: Long, change: (ReviewLine) -> ReviewLine) {
        mutableEdit.update { e -> e.copy(lines = e.lines.map { if (it.key == key) change(it) else it }) }
    }

    fun setLineText(key: Long, text: String) {
        val all = ingredients.value
        val known = aliases.value
        updateLine(key) { ReviewRules.withText(it, text, all, known) }
    }

    fun setLineGroup(key: Long, text: String) = updateLine(key) { it.copy(group = text) }

    fun setLineOptional(key: Long, optional: Boolean) = updateLine(key) { it.copy(optional = optional) }

    fun setLineAmount(key: Long, text: String) = updateLine(key) { ReviewRules.withQuantityText(it, text) }

    /** Back to the amount the line's text gives. */
    fun resetLineAmount(key: Long) = updateLine(key) { it.copy(quantityText = null, quantityKept = false) }

    fun chooseIngredient(key: Long, ingredientId: Long) {
        updateLine(key) { ReviewRules.choose(it, ingredientId) }
        if (mutableCreate.value?.lineKey == key) mutableCreate.value = null
    }

    fun markNotCounted(key: Long) {
        updateLine(key) { ReviewRules.notCounted(it) }
        if (mutableCreate.value?.lineKey == key) mutableCreate.value = null
    }

    /** Matches the line by its name again after it was marked not counted or an ingredient was chosen. */
    fun recount(key: Long) {
        val all = ingredients.value
        val known = aliases.value
        updateLine(key) { ReviewRules.recount(it, all, known) }
    }

    fun deleteLine(key: Long) {
        mutableEdit.update { e -> e.copy(lines = e.lines.filter { it.key != key }) }
        if (mutableCreate.value?.lineKey == key) mutableCreate.value = null
    }

    fun addLine() = mutableEdit.update { ReviewRules.addLine(it) }

    // ---- creating an ingredient for a line -------------------------------------------------------

    /** Opens the inline form for [key], with the name taken from the line. */
    fun startCreate(key: Long) {
        val line = mutableEdit.value.lines.firstOrNull { it.key == key } ?: return
        val name = IngredientLines.parse(line.text).name
        mutableCreate.value = CreateIngredientForm(lineKey = key, name = name.replaceFirstChar { it.uppercase() })
    }

    fun updateCreate(change: (CreateIngredientForm) -> CreateIngredientForm) {
        mutableCreate.update { form -> form?.let { change(it).copy(error = null) } }
    }

    fun cancelCreate() {
        mutableCreate.value = null
    }

    /** Saves the ingredient from the form, puts it on its line and closes the form. Shows what is wrong instead if it can't. */
    fun submitCreate() {
        val form = mutableCreate.value ?: return
        val packSize = IngredientFormRules.parseNumber(form.packSizeText)
        val price = if (form.priceText.isBlank()) null else IngredientFormRules.parsePrice(form.priceText)
        val problem = when {
            form.name.isBlank() -> "Give the ingredient a name."
            packSize == null || packSize <= 0.0 -> "The pack size must be a number above zero."
            form.priceText.isNotBlank() && price == null -> "The price must be an amount of money such as 0.99."
            else -> null
        }
        if (problem != null || packSize == null) {
            mutableCreate.update { it?.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            try {
                val id = ingredientRepository.save(
                    Ingredient(
                        name = form.name.trim(),
                        unit = form.unit,
                        packSize = packSize,
                        packLabel = form.packLabel.trim(),
                        price = price,
                        priceDate = if (price == null) null else clock.today(),
                        vegKind = form.vegKind,
                        shelf = form.shelf,
                    ),
                )
                // Let the ingredient list catch up, so the line never shows an ingredient the screen doesn't know yet.
                withTimeoutOrNull(3_000) { ingredients.first { list -> list.any { it.id == id } } }
                chooseIngredient(form.lineKey, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableCreate.update { it?.copy(error = "Couldn't create the ingredient: ${e.message ?: "unknown error"}") }
            }
        }
    }

    // ---- steps, tips, kit and storage ------------------------------------------------------------

    private fun updateStep(key: Long, change: (ReviewStep) -> ReviewStep) {
        mutableEdit.update { e -> e.copy(steps = e.steps.map { if (it.key == key) change(it) else it }) }
    }

    fun setStepTitle(key: Long, text: String) = updateStep(key) { it.copy(title = text) }

    fun setStepTime(key: Long, text: String) = updateStep(key) { it.copy(time = text) }

    fun setStepBody(key: Long, text: String) = updateStep(key) { it.copy(body = text) }

    fun deleteStep(key: Long) = mutableEdit.update { e -> e.copy(steps = e.steps.filter { it.key != key }) }

    fun addStep(kind: StepKind) = mutableEdit.update { ReviewRules.addStep(it, kind) }

    // ---- saving ------------------------------------------------------------------------------------

    /**
     * Saves the recipe through the repository (a new one as "mine", or the same id when editing), then learns
     * the name of every line whose ingredient was chosen by hand, and calls [onSaved] with the recipe's id.
     * Unmatched lines are allowed. Does nothing while the title or a number is not valid.
     */
    fun save(onSaved: (Long) -> Unit) {
        if (mutableSaving.value) return
        val current = mutableEdit.value
        val worked = ReviewRules.derive(current, base, ingredients.value.associateBy { it.id }, prices.value, countOptional.value)
        val problem = worked.problems.firstOrNull()
        if (problem != null) {
            mutableSaveError.value = problem
            return
        }
        mutableSaving.value = true
        mutableSaveError.value = null
        viewModelScope.launch {
            try {
                val id = recipeRepository.save(worked.recipe)
                for ((name, ingredientId) in ReviewRules.aliasesToLearn(current)) {
                    ingredientRepository.mapAlias(name, ingredientId)
                }
                onSaved(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableSaveError.value = "Couldn't save the recipe: ${e.message ?: "unknown error"}"
            } finally {
                mutableSaving.value = false
            }
        }
    }

    companion object {
        /** [recipeId] edits that recipe; [textPath] is a cache file of shared text for a new one (only files in the app's cache are read). */
        fun factory(recipeId: Long?, textPath: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                val container = app.container
                val file = textPath?.let { File(it) }?.takeIf { ReceiptFiles.isInside(app, it) }
                RecipeReviewViewModel(
                    ingredientRepository = container.ingredientRepository,
                    recipeRepository = container.recipeRepository,
                    receiptRepository = container.receiptRepository,
                    settingsStore = container.settingsStore,
                    clock = container.clock,
                    recipeId = recipeId,
                    textFile = file,
                )
            }
        }
    }
}
