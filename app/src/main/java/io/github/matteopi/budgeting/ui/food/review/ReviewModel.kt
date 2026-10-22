package io.github.matteopi.budgeting.ui.food.review

import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.ParsedLine
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeNumbers
import io.github.matteopi.budgeting.core.recipes.StepKind

/** How a line came by its ingredient (or lack of one). */
enum class LineOrigin {
    /** Matched by name (or not matched), and matched again whenever the text changes. */
    AUTO,

    /** Chosen or created by hand: kept when the text changes, and its name is learned on save. */
    CHOSEN,

    /** Loaded from a saved recipe: kept as it is, with its saved quantity. */
    SAVED,

    /** The person said it doesn't count (water, say). */
    NOT_COUNTED,
}

/** One ingredient line of the review, as edited. */
data class ReviewLine(
    /** Stable for the life of the screen, for lazy-list keys and test tags. Counted from 1. */
    val key: Long,
    val group: String = "",
    val text: String = "",
    val optional: Boolean = false,
    /** The line's ingredient; a negative id is a food of the built-in list that saving adds ([ReviewFoods]). */
    val ingredientId: Long? = null,
    val origin: LineOrigin = LineOrigin.AUTO,
    /** Other ingredients that might be the one meant, best first. Only while [ingredientId] is null or a food. */
    val suggestionIds: List<Long> = emptyList(),
    /** The amount in the ingredient's unit as typed; null follows what the line's text says. */
    val quantityText: String? = null,
    /** True while [quantityText] is the saved quantity of a loaded line whose text hasn't been edited. */
    val quantityKept: Boolean = false,
)

/** A method step, tip, kit item or storage note of the review. */
data class ReviewStep(
    val key: Long,
    val kind: StepKind,
    val title: String = "",
    val time: String = "",
    val body: String = "",
)

/** Everything the review lets the person change, as typed. */
data class ReviewEdit(
    val title: String = "",
    val servesText: String = "2",
    val prepText: String = "",
    val cookText: String = "",
    val style: String = "",
    val tagsText: String = "",
    val about: String = "",
    val servedWith: String = "",
    val timeNote: String = "",
    val lines: List<ReviewLine> = emptyList(),
    val steps: List<ReviewStep> = emptyList(),
    /** The next free key for a line or step. */
    val nextKey: Long = 1,
)

/** What the review works out about one line. */
data class LineDerived(
    val parsed: ParsedLine,
    val ingredient: Ingredient?,
    /** The quantity the line will be saved with, in the ingredient's unit; null when it has none. */
    val quantity: Double?,
    /** What the amount field shows. */
    val amountText: String,
    /** The quantity is an estimate (a spoon of a dry ingredient, say) rather than a weight. */
    val estimated: Boolean,
    /** The typed amount isn't a number of zero or more. */
    val quantityInvalid: Boolean,
    /** The text alone doesn't give a quantity, so the person is asked for one. */
    val textGivesNoQuantity: Boolean,
    /** The amount field has been typed in and differs from what the text gives. */
    val quantityOverridden: Boolean,
)

/** The review worked out from [ReviewEdit] and the ingredients: the recipe it would save, its numbers and what is wrong. */
data class ReviewDerived(
    val lines: Map<Long, LineDerived>,
    val recipe: Recipe,
    val numbers: RecipeNumbers,
    val serves: Int,
    /** Lines with text that have no ingredient and weren't marked not counted. */
    val unmatched: Int,
    val notCounted: Int,
    /** Lines with an ingredient but no quantity. */
    val noQuantity: Int,
    /** Counted lines in all. */
    val counted: Int,
    val titleMissing: Boolean,
    val servesInvalid: Boolean,
    val prepInvalid: Boolean,
    val cookInvalid: Boolean,
    val anyQuantityInvalid: Boolean,
) {
    /** What stops the recipe being saved, in the order to fix it; empty when it can be saved. */
    val problems: List<String>
        get() = buildList {
            if (titleMissing) add("Give the recipe a title.")
            if (servesInvalid) add("Servings must be a whole number of 1 or more.")
            if (prepInvalid || cookInvalid) add("Prep and cook times must be minutes, such as 20.")
            if (anyQuantityInvalid) add("Fix the amounts marked in red.")
        }

    val canSave: Boolean get() = problems.isEmpty()
}
