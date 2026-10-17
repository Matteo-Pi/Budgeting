package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate

/*
 * Persisted recipe models (docs/PLAN.md §11.3). Plain data classes with defaults for optional
 * fields; the app's Room layer maps to and from these. Quantities are in the ingredient's base
 * unit (grams or millilitres) and nutrition is per 100 base units.
 */

/** What an ingredient is measured in. Every recipe quantity is in this unit. */
enum class BaseUnit { G, ML }

/** Whether an ingredient counts towards 5 a day: vegetables and fruit, or beans and lentils (capped at one portion). */
enum class VegKind { NONE, VEG, PULSE }

/** Where an ingredient is found in a shop. Declaration order is the order of the shopping list. */
enum class Shelf { FRUIT_VEG, MEAT, DAIRY, FROZEN, CUPBOARD, OILS, HERBS_SPICES, OTHER }

/** Where an ingredient's nutrition values came from. */
enum class NutritionSource { LABEL, BARCODE, TYPED, ESTIMATE }

enum class RecipeSource { MINE, CLAUDE, IMPORTED }

/** Steps, tips, equipment and storage notes are all stored as steps of a recipe, told apart by this. */
enum class StepKind { STEP, TIP, EQUIPMENT, STORAGE }

/** The eight nutrients a recipe shows. */
enum class Nutrient { KCAL, FAT, SATURATES, CARBS, SUGARS, FIBRE, PROTEIN, SALT }

/** Nutrition per 100 g or 100 ml of an ingredient; null means unknown (not zero). */
data class Nutrition(
    val kcal: Double? = null,
    val fat: Double? = null,
    val saturates: Double? = null,
    val carbs: Double? = null,
    val sugars: Double? = null,
    val fibre: Double? = null,
    val protein: Double? = null,
    val salt: Double? = null,
) {
    operator fun get(nutrient: Nutrient): Double? = when (nutrient) {
        Nutrient.KCAL -> kcal
        Nutrient.FAT -> fat
        Nutrient.SATURATES -> saturates
        Nutrient.CARBS -> carbs
        Nutrient.SUGARS -> sugars
        Nutrient.FIBRE -> fibre
        Nutrient.PROTEIN -> protein
        Nutrient.SALT -> salt
    }

    /** True when every value is unknown. */
    val isEmpty: Boolean get() = Nutrient.entries.all { get(it) == null }

    /** True when at least one value is unknown. */
    val hasUnknown: Boolean get() = Nutrient.entries.any { get(it) == null }
}

/** A named weight or volume, such as `each` = 150 g for an onion or `clove` = 5 g for garlic. */
data class Portion(val name: String, val amount: Double)

data class Ingredient(
    val id: Long = 0,
    val name: String,
    val unit: BaseUnit = BaseUnit.G,
    /** How much is in one pack, in [unit]. */
    val packSize: Double = 1.0,
    val packLabel: String = "",
    /** The typed pack price. A price paid on a receipt wins over it (see [Prices]). */
    val price: Money? = null,
    val priceDate: LocalDate? = null,
    val store: String? = null,
    /** The receipts product this ingredient is linked to, if any. */
    val productId: Long? = null,
    /** The shop's own name for it. Text only. */
    val productName: String? = null,
    val sourceUrl: String? = null,
    val barcode: String? = null,
    val per100: Nutrition = Nutrition(),
    val nutritionSource: NutritionSource = NutritionSource.TYPED,
    val vegKind: VegKind = VegKind.NONE,
    val shelf: Shelf = Shelf.OTHER,
    val note: String = "",
    val archived: Boolean = false,
    val createdAtEpochMillis: Long = 0,
    val portions: List<Portion> = emptyList(),
)

/**
 * One ingredient line of a recipe. [text] is the line as written; [quantity] is the amount in the
 * ingredient's base unit (null when the line isn't counted); [ingredientId] is null for a line that
 * isn't matched to an ingredient.
 */
data class RecipeLine(
    val group: String? = null,
    val text: String,
    val ingredientId: Long? = null,
    val quantity: Double? = null,
    val optional: Boolean = false,
)

/** A method step, or a tip, an equipment item or a storage note (see [StepKind]). [time] is free text such as "~10 min". */
data class RecipeStep(
    val kind: StepKind = StepKind.STEP,
    val title: String? = null,
    val time: String? = null,
    val body: String,
)

data class Recipe(
    val id: Long = 0,
    val title: String,
    val shortTitle: String? = null,
    val tagline: String? = null,
    /** For example "Hob · one pot". */
    val style: String? = null,
    val serves: Int = 1,
    val prepMins: Int? = null,
    val cookMins: Int? = null,
    val timeNote: String? = null,
    val servedWith: String? = null,
    val tags: List<String> = emptyList(),
    /** 0 (not rated) to 5. */
    val rating: Int = 0,
    val notes: String = "",
    val source: RecipeSource = RecipeSource.MINE,
    val createdAtEpochMillis: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val archived: Boolean = false,
    val lines: List<RecipeLine> = emptyList(),
    /** Method steps, tips, equipment and storage notes together, in order. */
    val steps: List<RecipeStep> = emptyList(),
) {
    val method: List<RecipeStep> get() = steps.filter { it.kind == StepKind.STEP }
    val tips: List<RecipeStep> get() = steps.filter { it.kind == StepKind.TIP }
    val equipment: List<RecipeStep> get() = steps.filter { it.kind == StepKind.EQUIPMENT }
    val storage: List<RecipeStep> get() = steps.filter { it.kind == StepKind.STORAGE }

    /** Prep plus cook time, or null when neither is known. */
    val totalMins: Int? get() = if (prepMins == null && cookMins == null) null else (prepMins ?: 0) + (cookMins ?: 0)
}

/**
 * An entry on the shopping list: either a recipe at a number of servings ([recipeId]), or an extra
 * item (an [ingredientId] with a [quantity] in its base unit, or [freeText]). [checked] is only used
 * by free-text items; ingredient lines are ticked through [ShoppingMark].
 */
data class ShoppingEntry(
    val id: Long = 0,
    val recipeId: Long? = null,
    val servings: Int? = null,
    val ingredientId: Long? = null,
    val quantity: Double? = null,
    val freeText: String? = null,
    val checked: Boolean = false,
    val addedAtEpochMillis: Long = 0,
)

/** The ticks of one ingredient line of the shopping list, which sums several entries. */
data class ShoppingMark(
    val ingredientId: Long,
    val checked: Boolean = false,
    val haveIt: Boolean = false,
)
