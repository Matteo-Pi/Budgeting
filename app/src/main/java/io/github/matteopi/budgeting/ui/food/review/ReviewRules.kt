package io.github.matteopi.budgeting.ui.food.review

import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientLines
import io.github.matteopi.budgeting.core.recipes.IngredientMatching
import io.github.matteopi.budgeting.core.recipes.MatchKind
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeDraft
import io.github.matteopi.budgeting.core.recipes.RecipeLine
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import io.github.matteopi.budgeting.core.recipes.StepKind

/**
 * The review's rules (docs/PLAN.md §11.4), kept apart from the view model so they can be unit-tested:
 * building the first [ReviewEdit] from a pasted draft or a saved recipe, matching lines to
 * ingredients, and working out the recipe, its numbers and what is wrong from the edit.
 */
object ReviewRules {

    /** Servings a recipe starts with when the text doesn't say. */
    const val DEFAULT_SERVES = 2

    /** The order steps, tips, kit and storage are saved in. */
    private val KIND_ORDER = listOf(StepKind.STEP, StepKind.TIP, StepKind.EQUIPMENT, StepKind.STORAGE)

    // ---- building the first edit -----------------------------------------------------------

    /** A quantity as typed into the amount field: 150 -> "150", 5.4 -> "5.4". */
    fun formatQuantity(quantity: Double): String = Quantities.plain(quantity, 2)

    /**
     * The review of a recipe read from text. Every line is matched with [IngredientMatching.match]; an
     * ingredient is applied only when the match says to ([io.github.matteopi.budgeting.core.recipes.IngredientMatch.ingredientId]),
     * otherwise the suggestions are kept for the person to choose from. With [foods], the food list fills in where
     * no ingredient fits ([matchLine]).
     */
    fun fromDraft(draft: RecipeDraft, ingredients: List<Ingredient>, aliases: Map<String, Long>, foods: Boolean = false): ReviewEdit {
        var key = 1L
        val lines = draft.lines.map { line ->
            matchLine(ReviewLine(key = key++, group = line.group.orEmpty(), text = line.text, optional = line.optional), ingredients, aliases, foods)
        }
        val steps = draft.steps.map { step ->
            ReviewStep(key = key++, kind = step.kind, title = step.title.orEmpty(), time = step.time.orEmpty(), body = step.body)
        }
        return ReviewEdit(
            title = draft.title,
            servesText = (draft.serves ?: DEFAULT_SERVES).toString(),
            prepText = draft.prepMins?.toString().orEmpty(),
            cookText = draft.cookMins?.toString().orEmpty(),
            style = draft.style.orEmpty(),
            tagsText = draft.tags.joinToString(", "),
            about = draft.tagline.orEmpty(),
            servedWith = draft.servedWith.orEmpty(),
            timeNote = draft.timeNote.orEmpty(),
            lines = lines,
            steps = steps,
            nextKey = key,
        )
    }

    /**
     * The review of a saved recipe. Lines keep their ingredient and quantity, and steps keep their
     * kinds. A line saved with no ingredient is matched again (the ingredient may exist now) and is
     * "not counted" if nothing fits, so editing doesn't ask about it again.
     */
    fun fromRecipe(recipe: Recipe, ingredients: List<Ingredient>, aliases: Map<String, Long>): ReviewEdit {
        var key = 1L
        val lines = recipe.lines.map { saved ->
            val line = ReviewLine(
                key = key++,
                group = saved.group.orEmpty(),
                text = saved.text,
                optional = saved.optional,
                ingredientId = saved.ingredientId,
                origin = LineOrigin.SAVED,
                quantityText = if (saved.ingredientId != null) saved.quantity?.let { formatQuantity(it) } else null,
                quantityKept = saved.ingredientId != null && saved.quantity != null,
            )
            if (saved.ingredientId != null) {
                line
            } else {
                // Not from the food list: a recipe opened to change its title shouldn't add ingredients on save.
                val matched = matchLine(line.copy(origin = LineOrigin.AUTO), ingredients, aliases)
                if (matched.ingredientId != null) matched else notCounted(matched)
            }
        }
        val steps = recipe.steps.map { step ->
            ReviewStep(key = key++, kind = step.kind, title = step.title.orEmpty(), time = step.time.orEmpty(), body = step.body)
        }
        return ReviewEdit(
            title = recipe.title,
            servesText = recipe.serves.toString(),
            prepText = recipe.prepMins?.toString().orEmpty(),
            cookText = recipe.cookMins?.toString().orEmpty(),
            style = recipe.style.orEmpty(),
            tagsText = recipe.tags.joinToString(", "),
            about = recipe.tagline.orEmpty(),
            servedWith = recipe.servedWith.orEmpty(),
            timeNote = recipe.timeNote.orEmpty(),
            lines = lines,
            steps = steps,
            nextKey = key,
        )
    }

    // ---- changing a line --------------------------------------------------------------------

    /**
     * Matches [line]'s text to an ingredient: applied when sure ([LineOrigin.AUTO]), else the suggestions are kept.
     * With [foods], a line none of the ingredients fits takes a food of the built-in list when one fits
     * ([ReviewFoods], docs/PLAN.md §11.12), keeping the suggestions too; a food with the name of one of the
     * ingredients ("rice" is the list's *Long-grain white rice*) gives that ingredient. So does a line weighed in
     * the other unit from the ingredient its name found, when a food in the line's unit fits: "500 ml chicken stock"
     * is the list's stock made with a cube rather than 500 g of the person's stock cubes, which stay one tap away.
     * A name the person taught is kept whatever the unit.
     */
    fun matchLine(line: ReviewLine, ingredients: List<Ingredient>, aliases: Map<String, Long>, foods: Boolean = false): ReviewLine {
        val parsed = IngredientLines.parse(line.text)
        val name = parsed.name
        val unit = parsed.measure?.let { Quantities.unitOf(it) }
        val match = IngredientMatching.match(name, ingredients, aliases, unit)
        val suggestions = match.suggestions.map { it.ingredientId }
        val found = match.ingredientId?.let { id -> ingredients.firstOrNull { it.id == id } }
        val otherUnit = unit != null && found != null && found.unit != unit && match.kind != MatchKind.ALIAS
        val food = if (foods && (found == null || otherUnit)) {
            FoodDatabase.match(name, line.text)?.takeIf { found == null || it.ingredient.unit == unit }
        } else {
            null
        }
        val same = food?.let { f -> ingredients.firstOrNull { !it.archived && it.name.equals(f.name, ignoreCase = true) } }
        val id = if (food != null) same?.id else match.ingredientId
        return when {
            id != null -> line.copy(ingredientId = id, origin = LineOrigin.AUTO, suggestionIds = emptyList())
            food != null -> line.copy(ingredientId = ReviewFoods.idOf(food), origin = LineOrigin.AUTO, suggestionIds = suggestions)
            else -> line.copy(ingredientId = null, origin = LineOrigin.AUTO, suggestionIds = suggestions)
        }
    }

    /**
     * [line] with new text. A line that is matched automatically is matched again; one chosen by hand, loaded
     * or marked not counted keeps its ingredient. A saved quantity is dropped once the text changes (the text
     * is the source again); a quantity the person typed stays.
     */
    fun withText(line: ReviewLine, text: String, ingredients: List<Ingredient>, aliases: Map<String, Long>, foods: Boolean = false): ReviewLine {
        val changed = line.copy(
            text = text,
            quantityText = if (line.quantityKept) null else line.quantityText,
            quantityKept = false,
        )
        return if (changed.origin == LineOrigin.AUTO) matchLine(changed, ingredients, aliases, foods) else changed
    }

    /** The person chose [ingredientId] for the line: it is kept and its name is learned when the recipe is saved. */
    fun choose(line: ReviewLine, ingredientId: Long): ReviewLine = line.copy(
        ingredientId = ingredientId,
        origin = LineOrigin.CHOSEN,
        suggestionIds = emptyList(),
        quantityText = null,
        quantityKept = false,
    )

    /** The line doesn't count towards the numbers (water, say). */
    fun notCounted(line: ReviewLine): ReviewLine = line.copy(
        ingredientId = null,
        origin = LineOrigin.NOT_COUNTED,
        suggestionIds = emptyList(),
        quantityText = null,
        quantityKept = false,
    )

    /** Back to matching the line by its name. */
    fun recount(line: ReviewLine, ingredients: List<Ingredient>, aliases: Map<String, Long>, foods: Boolean = false): ReviewLine =
        matchLine(line.copy(quantityText = null, quantityKept = false), ingredients, aliases, foods)

    /** The amount typed into the line's amount field (in the ingredient's unit). */
    fun withQuantityText(line: ReviewLine, text: String): ReviewLine = line.copy(quantityText = text, quantityKept = false)

    fun addLine(edit: ReviewEdit): ReviewEdit = edit.copy(
        lines = edit.lines + ReviewLine(key = edit.nextKey),
        nextKey = edit.nextKey + 1,
    )

    fun addStep(edit: ReviewEdit, kind: StepKind): ReviewEdit = edit.copy(
        steps = edit.steps + ReviewStep(key = edit.nextKey, kind = kind),
        nextKey = edit.nextKey + 1,
    )

    // ---- working it out ---------------------------------------------------------------------

    /** Minutes typed into a time field: blank is none (null, fine); "20", "1 hr 15 min" and "1½ hours" read; anything else is invalid. */
    private fun minutes(text: String): Pair<Int?, Boolean> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null to false
        val value = RecipeText.parseMinutes(trimmed)?.takeIf { it >= 0 }
        return value to (value == null)
    }

    private fun tags(text: String): List<String> =
        text.split(',').map { it.trim().trimStart('#').trim() }.filter { it.isNotEmpty() }

    /**
     * What the review shows and saves. [ingredients] is every ingredient by id (archived ones too, so a
     * saved line keeps its numbers), [prices] the resolved prices (`Prices.resolveAll`) and
     * [countOptional] the "count optional extras" setting. [base] is the recipe being edited, whose id,
     * short title, rating, notes, source and dates are kept; null for a new recipe.
     */
    fun derive(
        edit: ReviewEdit,
        base: Recipe?,
        ingredients: Map<Long, Ingredient>,
        prices: Map<Long, ResolvedPrice>,
        countOptional: Boolean,
    ): ReviewDerived {
        val perLine = LinkedHashMap<Long, LineDerived>()
        val recipeLines = ArrayList<RecipeLine>()
        var unmatched = 0
        var notCounted = 0
        var noQuantity = 0
        var counted = 0
        var anyInvalid = false

        for (line in edit.lines) {
            val text = line.text.trim()
            val parsed = IngredientLines.parse(text)
            val ingredient = line.ingredientId?.let { ingredients[it] }
            val resolved = if (ingredient != null && text.isNotEmpty()) IngredientLines.resolve(parsed, ingredient) else null
            val typed = line.quantityText
            var quantity: Double? = null
            var invalid = false
            if (ingredient != null) {
                if (typed != null) {
                    if (typed.isBlank()) {
                        quantity = null
                    } else {
                        val value = typed.trim().replace(',', '.').toDoubleOrNull()
                        if (value == null || value.isNaN() || value.isInfinite() || value < 0.0) invalid = true else quantity = value
                    }
                } else {
                    quantity = resolved?.quantity
                }
            }
            val overridden = typed != null && resolved != null && (quantity == null || Math.abs(quantity - resolved.quantity) > 1e-6)
            perLine[line.key] = LineDerived(
                parsed = parsed,
                ingredient = ingredient,
                quantity = quantity,
                amountText = typed ?: resolved?.let { formatQuantity(it.quantity) }.orEmpty(),
                estimated = typed == null && resolved?.estimated == true,
                quantityInvalid = invalid,
                textGivesNoQuantity = ingredient != null && resolved == null,
                quantityOverridden = overridden,
            )
            if (invalid) anyInvalid = true
            if (text.isEmpty()) continue
            when {
                line.origin == LineOrigin.NOT_COUNTED -> notCounted++
                ingredient == null -> unmatched++
                quantity == null -> noQuantity++
                else -> counted++
            }
            recipeLines += RecipeLine(
                group = line.group.trim().ifEmpty { null },
                text = text,
                ingredientId = ingredient?.id,
                quantity = if (ingredient == null) null else quantity,
                optional = line.optional,
            )
        }

        val serves = edit.servesText.trim().toIntOrNull()
        val (prep, prepInvalid) = minutes(edit.prepText)
        val (cook, cookInvalid) = minutes(edit.cookText)
        val steps = KIND_ORDER.flatMap { kind ->
            edit.steps.filter { it.kind == kind }.mapNotNull { step ->
                val body = step.body.trim()
                if (kind == StepKind.STEP) {
                    val title = step.title.trim()
                    val time = step.time.trim()
                    if (title.isEmpty() && time.isEmpty() && body.isEmpty()) {
                        null
                    } else {
                        RecipeStep(kind, title.ifEmpty { null }, time.ifEmpty { null }, body)
                    }
                } else if (body.isEmpty()) {
                    null
                } else {
                    RecipeStep(kind, null, null, body)
                }
            }
        }
        val recipe = (base ?: Recipe(title = "")).copy(
            title = edit.title.trim(),
            tagline = edit.about.trim().ifEmpty { null },
            style = edit.style.trim().ifEmpty { null },
            serves = serves?.coerceAtLeast(1) ?: 1,
            prepMins = prep,
            cookMins = cook,
            timeNote = edit.timeNote.trim().ifEmpty { null },
            servedWith = edit.servedWith.trim().ifEmpty { null },
            tags = tags(edit.tagsText),
            lines = recipeLines,
            steps = steps,
        )
        val numbers = RecipeMaths.analyse(recipe, ingredients, prices, recipe.serves, countOptional)
        return ReviewDerived(
            lines = perLine,
            recipe = recipe,
            numbers = numbers,
            serves = recipe.serves,
            unmatched = unmatched,
            notCounted = notCounted,
            noQuantity = noQuantity,
            counted = counted,
            titleMissing = edit.title.isBlank(),
            servesInvalid = serves == null || serves < 1,
            prepInvalid = prepInvalid,
            cookInvalid = cookInvalid,
            anyQuantityInvalid = anyInvalid,
        )
    }

    /**
     * The names to learn when the recipe is saved: the parsed name of every line whose ingredient the person
     * chose by hand (or created), or that takes a food of the food list (its provisional id, which the save swaps
     * for the new ingredient's), with that ingredient. The repository folds each name with
     * [IngredientMatching.nameKey].
     */
    fun aliasesToLearn(edit: ReviewEdit): List<Pair<String, Long>> =
        edit.lines.mapNotNull { line ->
            val id = line.ingredientId
            if ((line.origin != LineOrigin.CHOSEN && !ReviewFoods.isFood(id)) || id == null || line.text.isBlank()) {
                null
            } else {
                val name = IngredientLines.parse(line.text).name
                if (IngredientMatching.nameKey(name).isEmpty()) null else name to id
            }
        }
}
