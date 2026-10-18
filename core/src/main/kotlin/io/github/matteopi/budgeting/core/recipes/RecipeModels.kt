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

/**
 * The nutrients a recipe shows: the eight of a UK label ([LABEL]) and added sugars, which labels don't list
 * (docs/PLAN.md §11.11).
 */
enum class Nutrient {
    KCAL, FAT, SATURATES, CARBS, SUGARS, ADDED_SUGARS, FIBRE, PROTEIN, SALT;

    companion object {
        /** The eight values on a UK label, in its order: every nutrient but [ADDED_SUGARS]. */
        val LABEL: List<Nutrient> = listOf(KCAL, FAT, SATURATES, CARBS, SUGARS, FIBRE, PROTEIN, SALT)
    }
}

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
    /** Sugar, honey and syrups put into the food. Declared last so positional construction keeps working. */
    val addedSugars: Double? = null,
) {
    operator fun get(nutrient: Nutrient): Double? = when (nutrient) {
        Nutrient.KCAL -> kcal
        Nutrient.FAT -> fat
        Nutrient.SATURATES -> saturates
        Nutrient.CARBS -> carbs
        Nutrient.SUGARS -> sugars
        Nutrient.ADDED_SUGARS -> addedSugars
        Nutrient.FIBRE -> fibre
        Nutrient.PROTEIN -> protein
        Nutrient.SALT -> salt
    }

    /** True when every value is unknown. */
    val isEmpty: Boolean get() = Nutrient.entries.all { get(it) == null }

    /** True when at least one of the eight label values is unknown (added sugars, which labels don't list, aside). */
    val hasUnknown: Boolean get() = Nutrient.LABEL.any { get(it) == null }
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
    /** What the recipe filters count it as (docs/PLAN.md §11.11): null guesses from the name ([FoodGroups]). */
    val foodGroup: FoodGroup? = null,
)

/** Whether a food group is a recipe's protein or its carb. */
enum class FoodGroupKind { PROTEIN, CARB }

/**
 * What an ingredient counts as in the recipe filters (docs/PLAN.md §11.11). [NONE] is the person's "Neither";
 * an ingredient with no group set is guessed by [FoodGroups].
 */
enum class FoodGroup(val label: String, val kind: FoodGroupKind?) {
    CHICKEN("Chicken", FoodGroupKind.PROTEIN),
    TURKEY("Turkey", FoodGroupKind.PROTEIN),
    BEEF("Beef", FoodGroupKind.PROTEIN),
    PORK("Pork, bacon & ham", FoodGroupKind.PROTEIN),
    LAMB("Lamb", FoodGroupKind.PROTEIN),
    FISH("Fish", FoodGroupKind.PROTEIN),
    SEAFOOD("Prawns & seafood", FoodGroupKind.PROTEIN),
    EGGS("Eggs", FoodGroupKind.PROTEIN),
    BEANS_LENTILS("Beans & lentils", FoodGroupKind.PROTEIN),
    MEAT_FREE("Meat-free (tofu, Quorn…)", FoodGroupKind.PROTEIN),
    RICE("Rice", FoodGroupKind.CARB),
    PASTA("Pasta", FoodGroupKind.CARB),
    NOODLES("Noodles", FoodGroupKind.CARB),
    POTATOES("Potatoes", FoodGroupKind.CARB),
    BREAD("Bread & wraps", FoodGroupKind.CARB),
    GRAINS("Couscous & grains", FoodGroupKind.CARB),
    NONE("Neither", null),
    ;

    /** True for the meats and fish (not eggs, pulses or meat-free). */
    val isMeatOrFish: Boolean get() = this in setOf(CHICKEN, TURKEY, BEEF, PORK, LAMB, FISH, SEAFOOD)
}

/**
 * Something the person has in the kitchen (the `owned_items` table, docs/PLAN.md §11.11). [quantity] is in the
 * ingredient's base unit; null means "some, not measured".
 */
data class OwnedItem(
    val ingredientId: Long,
    val quantity: Double? = null,
    val updatedAtEpochMillis: Long = 0,
)

/**
 * A learned name for an ingredient (the `ingredient_aliases` table): [nameKey] is
 * `IngredientMatching.nameKey(...)` of a name that means [ingredientId]. Each key belongs to one ingredient.
 */
data class IngredientAliasRecord(
    val id: Long = 0,
    val nameKey: String,
    val ingredientId: Long,
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
