package io.github.matteopi.budgeting.ui.food.review

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.RecipeText
import io.github.matteopi.budgeting.core.recipes.StepKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review's rules on the starter book: the chilli pasted as text is matched line by line, the numbers
 * are the §11.7 checkpoint ones, and editing a saved recipe keeps its ingredients and quantities.
 */
class ReviewRulesTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val models = RecipeBundle.starter().toModels()
    private val ingredients: List<Ingredient> = models.ingredients
    private val ingredientsById = ingredients.associateBy { it.id }
    private val prices = Prices.resolveAll(ingredients, emptyList(), today)
    private val chilli: Recipe = models.recipes.first { it.title.startsWith("Beef, Bean") }
    private val pasted = RecipeText.format(chilli.copy(title = "My chilli"))

    private fun derive(edit: ReviewEdit, base: Recipe? = null, countOptional: Boolean = true) =
        ReviewRules.derive(edit, base, ingredientsById, prices, countOptional)

    private fun lineWith(edit: ReviewEdit, fragment: String): ReviewLine = edit.lines.first { fragment in it.text }

    /** What the person does on the review: take the best suggestion of every line the matching left open, and skip the water. */
    private fun acceptSuggestionsAndSkipWater(edit: ReviewEdit): ReviewEdit = edit.copy(
        lines = edit.lines.map { line ->
            when {
                line.ingredientId != null -> line
                "boiling water" in line.text -> ReviewRules.notCounted(line)
                else -> ReviewRules.choose(line, line.suggestionIds.first())
            }
        },
    )

    // ---- a pasted recipe ---------------------------------------------------------------------

    @Test
    fun theChilliPastedAsTextLeavesTheWaterTheRiceAndTheLemonToBeChosen() {
        val draft = RecipeText.parse(pasted)
        assertEquals("My chilli", draft.title)
        val edit = ReviewRules.fromDraft(draft, ingredients, emptyMap())

        assertEquals(chilli.lines.size, edit.lines.size)
        // "rice" and "lemon or a lime" are only close to "Long-grain white rice" and "Lemon (juice)": the matching
        // offers them as suggestions and applies nothing, and nothing is close to the water.
        val open = edit.lines.filter { it.ingredientId == null }
        assertEquals(3, open.size, "$open")
        val water = open.single { "boiling water" in it.text }
        assertTrue(water.suggestionIds.isEmpty())
        val rice = open.single { "rice" in it.text }
        assertEquals(listOf("Long-grain white rice"), rice.suggestionIds.map { ingredientsById.getValue(it).name })
        val lemon = open.single { "lemon" in it.text }
        assertEquals(listOf("Lemon (juice)"), lemon.suggestionIds.map { ingredientsById.getValue(it).name })
        assertTrue(edit.lines.filter { it.ingredientId != null }.all { it.origin == LineOrigin.AUTO })
        assertEquals("4", edit.servesText)
        assertEquals("20", edit.prepText)
        assertEquals("55", edit.cookText)
        assertTrue(edit.steps.any { it.kind == StepKind.STEP })
        assertTrue(edit.steps.any { it.kind == StepKind.STORAGE })
    }

    @Test
    fun withTheSuggestionsTakenAndTheWaterNotCountedTheNumbersAreTheCheckpointOnes() {
        val edit = ReviewRules.fromDraft(RecipeText.parse(pasted), ingredients, emptyMap())
        val before = derive(edit)
        assertEquals(3, before.unmatched)
        assertTrue(before.numbers.cost.perPortion < Money(254), "without the rice and lemon the cost is lower")

        val done = acceptSuggestionsAndSkipWater(edit)
        val after = derive(done)
        assertEquals(0, after.unmatched)
        assertEquals(1, after.notCounted)
        // The salt-to-taste line is matched to Salt but gives no amount: it asks for a weight and counts for nothing.
        assertEquals(1, after.noQuantity)
        val salt = after.lines.getValue(lineWith(edit, "Salt only after tasting").key)
        assertEquals("Salt", salt.ingredient?.name)
        assertTrue(salt.textGivesNoQuantity)
        assertNull(salt.quantity)

        assertEquals(Money(254), after.numbers.cost.perPortion)
        assertEquals("897", RecipeMaths.formatNutrient(Nutrient.KCAL, after.numbers.nutrition.perPortion.kcal))
        assertTrue(after.canSave)
        assertEquals(RecipeMaths.analyse(chilli, ingredientsById, prices).cost.perPortion, after.numbers.cost.perPortion)
        // The two suggestions taken by hand are the names to learn.
        assertEquals(setOf("rice", "lemon or a lime"), ReviewRules.aliasesToLearn(done).map { it.first }.toSet())
    }

    @Test
    fun everyResolvedQuantityIsTheBundlesOwn() {
        val edit = acceptSuggestionsAndSkipWater(ReviewRules.fromDraft(RecipeText.parse(pasted), ingredients, emptyMap()))
        val derived = derive(edit)
        for ((line, saved) in edit.lines.zip(chilli.lines)) {
            if (saved.ingredientId == null || "Salt only" in saved.text) continue
            val got = derived.lines.getValue(line.key).quantity
            assertNotNull(got, saved.text)
            assertEquals(saved.quantity!!, got, 0.011, saved.text)
        }
    }

    @Test
    fun anEstimatedWeightIsFlagged() {
        val pot = ingredients.first { it.name == "Dried mixed herbs" }.copy(portions = emptyList())
        val edit = ReviewEdit(lines = listOf(ReviewLine(key = 1, text = "2 tsp mixed herbs", ingredientId = pot.id, origin = LineOrigin.CHOSEN)))
        val derived = ReviewRules.derive(edit, null, ingredientsById + (pot.id to pot), prices, true)
        val line = derived.lines.getValue(1)
        assertTrue(line.estimated)
        assertEquals("10", line.amountText)
    }

    // ---- the food list (docs/PLAN.md §11.12) ---------------------------------------------------

    private val curry = "# Chickpea pilau\nServes: 2\n\n## Ingredients\n- 240 g chickpeas\n- 1 red onion\n- 150 g basmati rice\n" +
        "- 400 g rice\n- 300 ml water\n\n## Method\n1. Cook it all.\n"

    @Test
    fun linesNoIngredientFitsTakeAFoodAndCountInTheNumbers() {
        val edit = ReviewRules.fromDraft(RecipeText.parse(curry), ingredients, emptyMap(), foods = true)
        val names = edit.lines.map { line -> line.ingredientId?.let { (ingredientsById[it] ?: ReviewFoods.ingredients.getValue(it)).name } }
        // Chickpeas, red onions and basmati rice are only on the food list; "rice" is the list's name for the book's
        // own Long-grain white rice, so that line takes the ingredient; nothing is called water.
        assertEquals(listOf("Chickpeas (tin, drained)", "Red onions", "Basmati rice", "Long-grain white rice", null), names)
        assertEquals(listOf(true, true, true, false, false), edit.lines.map { ReviewFoods.isFood(it.ingredientId) })
        assertTrue(edit.lines.all { it.origin == LineOrigin.AUTO })
        // "red onion" is close to the book's Brown onions too, which stays one tap away.
        val onion = edit.lines[1]
        assertEquals("Brown onions", ingredientsById.getValue(onion.suggestionIds.first()).name)

        val noRice = edit.copy(lines = edit.lines.filter { it.text != "400 g rice" }.map { if ("water" in it.text) ReviewRules.notCounted(it) else it })
        val derived = ReviewRules.derive(noRice, null, ingredientsById + ReviewFoods.ingredients, prices + ReviewFoods.prices(today), true)
        assertEquals(0, derived.unmatched)
        assertEquals(3, derived.counted)
        // 41p of chickpeas, 120 g of a 95p kilo of onions, 150 g of a £1.79 kilo of rice: 79.25p for two.
        assertEquals(79.25, derived.numbers.cost.totalPence, 1e-9)
        assertEquals(Money(40), derived.numbers.cost.perPortion)
        assertEquals(120.0, derived.lines.getValue(onion.key).quantity)

        // Saving learns the names of the lines that took a food (with their provisional ids, swapped on save).
        assertEquals(
            setOf("chickpeas", "red onion", "basmati rice"),
            ReviewRules.aliasesToLearn(noRice).map { it.first }.toSet(),
        )
        assertTrue(ReviewRules.aliasesToLearn(noRice).all { ReviewFoods.isFood(it.second) })
    }

    @Test
    fun theFoodListIsOnlyAskedWhenWantedAndNeverForASavedRecipe() {
        val plain = ReviewRules.fromDraft(RecipeText.parse(curry), ingredients, emptyMap())
        assertTrue(plain.lines.none { ReviewFoods.isFood(it.ingredientId) })

        val saved = chilli.copy(lines = chilli.lines + chilli.lines.first().copy(text = "240 g chickpeas", ingredientId = null, quantity = null))
        val opened = ReviewRules.fromRecipe(saved, ingredients, emptyMap())
        assertEquals(LineOrigin.NOT_COUNTED, opened.lines.last().origin)

        // Typing a line again asks the list; a food chosen by hand stays when the text changes.
        val line = ReviewRules.withText(opened.lines.last().copy(origin = LineOrigin.AUTO), "1 tin chickpeas", ingredients, emptyMap(), foods = true)
        assertEquals("Chickpeas (tin, drained)", ReviewFoods.food(line.ingredientId!!)?.name)
        val butterBeans = ReviewFoods.idOf(FoodDatabase.byKey("butter_beans")!!)
        val chosen = ReviewRules.choose(line, butterBeans)
        assertEquals(butterBeans, ReviewRules.withText(chosen, "1 tin of beans", ingredients, emptyMap(), foods = true).ingredientId)
    }

    // ---- editing a line -----------------------------------------------------------------------

    @Test
    fun aTypedAmountWinsOverTheTextAndAnInvalidOneBlocksSaving() {
        val edit = ReviewRules.fromDraft(RecipeText.parse(pasted), ingredients, emptyMap())
        val onion = lineWith(edit, "onions")
        val typed = edit.copy(lines = edit.lines.map { if (it.key == onion.key) ReviewRules.withQuantityText(it, "200") else it })
        val derived = derive(typed)
        assertEquals(200.0, derived.lines.getValue(onion.key).quantity)
        assertTrue(derived.lines.getValue(onion.key).quantityOverridden)

        val broken = typed.copy(lines = typed.lines.map { if (it.key == onion.key) ReviewRules.withQuantityText(it, "lots") else it })
        val bad = derive(broken)
        assertTrue(bad.lines.getValue(onion.key).quantityInvalid)
        assertFalse(bad.canSave)
        assertTrue("Fix the amounts marked in red." in bad.problems)

        val emptied = typed.copy(lines = typed.lines.map { if (it.key == onion.key) ReviewRules.withQuantityText(it, "") else it })
        assertNull(derive(emptied).lines.getValue(onion.key).quantity)
        assertTrue(derive(emptied).canSave)
    }

    @Test
    fun editingTheTextOfAnAutomaticLineMatchesItAgainButAChosenOneKeepsItsIngredient() {
        val edit = ReviewRules.fromDraft(RecipeText.parse(pasted), ingredients, emptyMap())
        val cumin = lineWith(edit, "cumin")
        val changed = ReviewRules.withText(cumin, "2 tsp smoked paprika", ingredients, emptyMap())
        assertEquals("Smoked paprika", ingredientsById.getValue(changed.ingredientId!!).name)

        val chosen = ReviewRules.choose(cumin, ingredients.first { it.name == "Salt" }.id)
        val kept = ReviewRules.withText(chosen, "2 tsp smoked paprika", ingredients, emptyMap())
        assertEquals(chosen.ingredientId, kept.ingredientId)
        assertEquals(LineOrigin.CHOSEN, kept.origin)

        val skipped = ReviewRules.notCounted(cumin)
        assertNull(skipped.ingredientId)
        assertEquals(LineOrigin.NOT_COUNTED, ReviewRules.withText(skipped, "something else", ingredients, emptyMap()).origin)
        assertEquals(cumin.ingredientId, ReviewRules.recount(skipped, ingredients, emptyMap()).ingredientId)
    }

    @Test
    fun onlyLinesChosenByHandTeachTheirNames() {
        val edit = ReviewRules.fromDraft(RecipeText.parse(pasted), ingredients, emptyMap())
        assertTrue(ReviewRules.aliasesToLearn(edit).isEmpty())

        val water = lineWith(edit, "boiling water")
        val stock = ingredients.first { it.name == "Chicken stock cubes" }
        val taught = edit.copy(lines = edit.lines.map { if (it.key == water.key) ReviewRules.choose(it, stock.id) else it })
        assertEquals(listOf("boiling water" to stock.id), ReviewRules.aliasesToLearn(taught))
    }

    @Test
    fun anAmountlessLineWithAChosenIngredientCanBeGivenAWeight() {
        val edit = ReviewEdit(
            lines = listOf(ReviewLine(key = 1, text = "salt to taste", ingredientId = ingredients.first { it.name == "Salt" }.id, origin = LineOrigin.CHOSEN)),
        )
        assertEquals(1, derive(edit).noQuantity)
        val weighed = edit.copy(lines = listOf(ReviewRules.withQuantityText(edit.lines[0], "1.5")))
        assertEquals(0, derive(weighed).noQuantity)
        assertEquals(1, derive(weighed).counted)
    }

    // ---- a new ingredient, then matching it ----------------------------------------------------

    @Test
    fun anIngredientCreatedForAHalloumiLineGivesTheCostAndMatchesNextTime() {
        val halloumi = Ingredient(
            id = 100,
            name = "Halloumi",
            unit = BaseUnit.G,
            packSize = 225.0,
            price = Money(250),
            priceDate = today,
        )
        val all = ingredients + halloumi
        val byId = all.associateBy { it.id }
        val allPrices = Prices.resolveAll(all, emptyList(), today)

        var edit = ReviewEdit(title = "Halloumi wrap", servesText = "2")
        edit = ReviewRules.addLine(edit)
        edit = ReviewRules.addLine(edit)
        edit = edit.copy(
            lines = listOf(
                ReviewRules.withText(edit.lines[0], "200 g halloumi", ingredients, emptyMap()),
                ReviewRules.withText(edit.lines[1], "1 tbsp olive oil", ingredients, emptyMap()),
            ),
        )
        assertNull(edit.lines[0].ingredientId, "halloumi isn't in the book yet")
        assertNotNull(edit.lines[1].ingredientId)
        val without = ReviewRules.derive(edit, null, ingredientsById, prices, true)
        assertEquals(1, without.unmatched)
        assertEquals(Money(5), without.numbers.cost.perPortion, "only the olive oil counts until the halloumi has an ingredient")

        edit = edit.copy(lines = listOf(ReviewRules.choose(edit.lines[0], halloumi.id), edit.lines[1]))
        val with = ReviewRules.derive(edit, null, byId, allPrices, true)
        assertEquals(0, with.unmatched)
        assertFalse(with.numbers.cost.atLeast)
        // 200 g of 225 g at £2.50 = 222.2p, plus 15 ml of £6.75 a litre = 10.1p, over 2 servings.
        assertEquals(Money(116), with.numbers.cost.perPortion)
        assertEquals(listOf("halloumi" to halloumi.id), ReviewRules.aliasesToLearn(edit))

        val next = ReviewRules.matchLine(ReviewLine(key = 1, text = "100 g halloumi"), all, emptyMap())
        assertEquals(halloumi.id, next.ingredientId)
        assertEquals(LineOrigin.AUTO, next.origin)
    }

    // ---- editing a saved recipe -----------------------------------------------------------------

    @Test
    fun editingASavedRecipeKeepsItsLinesQuantitiesAndSteps() {
        val edit = ReviewRules.fromRecipe(chilli, ingredients, emptyMap())
        assertEquals(chilli.lines.size, edit.lines.size)
        val saltLine = lineWith(edit, "Salt only after tasting")
        assertEquals(LineOrigin.SAVED, saltLine.origin)
        assertEquals("1.5", saltLine.quantityText)
        val water = lineWith(edit, "boiling water")
        assertEquals(LineOrigin.NOT_COUNTED, water.origin, "a line saved without an ingredient isn't asked about again")

        val derived = derive(edit, base = chilli)
        assertEquals(chilli.id, derived.recipe.id)
        assertEquals(chilli.shortTitle, derived.recipe.shortTitle)
        assertEquals(chilli.createdAtEpochMillis, derived.recipe.createdAtEpochMillis)
        assertEquals(chilli.lines.map { it.quantity }, derived.recipe.lines.map { it.quantity })
        assertEquals(chilli.lines.map { it.ingredientId }, derived.recipe.lines.map { it.ingredientId })
        assertEquals(chilli.steps.map { it.kind to it.body }, derived.recipe.steps.map { it.kind to it.body })
        assertEquals(chilli.tags, derived.recipe.tags)
        assertEquals(Money(254), derived.numbers.cost.perPortion)
        assertEquals(0, derived.unmatched)
    }

    @Test
    fun editingTheTextOfASavedLineDropsItsSavedQuantity() {
        val edit = ReviewRules.fromRecipe(chilli, ingredients, emptyMap())
        val onions = lineWith(edit, "2 onions")
        assertEquals("300", onions.quantityText)
        val changed = ReviewRules.withText(onions, "3 onions, diced", ingredients, emptyMap())
        assertNull(changed.quantityText)
        val derived = derive(edit.copy(lines = edit.lines.map { if (it.key == onions.key) changed else it }), base = chilli)
        assertEquals(450.0, derived.lines.getValue(onions.key).quantity)
    }

    // ---- the header, steps and checks ----------------------------------------------------------

    @Test
    fun theHeaderIsCheckedAndBuiltIntoTheRecipe() {
        val edit = ReviewEdit(
            title = "  Toast  ",
            servesText = "0",
            prepText = "ten",
            cookText = "1 hr 15 min",
            tagsText = "#quick, easy,, ",
            about = " Crisp. ",
            steps = listOf(
                ReviewStep(1, StepKind.TIP, body = "Butter it"),
                ReviewStep(2, StepKind.STEP, title = "Toast", time = "~2 min", body = "Put the bread in."),
                ReviewStep(3, StepKind.STEP),
                ReviewStep(4, StepKind.STORAGE, body = "  "),
            ),
        )
        val derived = derive(edit)
        assertTrue(derived.servesInvalid)
        assertTrue(derived.prepInvalid)
        assertFalse(derived.cookInvalid)
        assertEquals(75, derived.recipe.cookMins)
        assertFalse(derived.canSave)
        assertEquals(2, derived.problems.size)
        assertEquals("Toast", derived.recipe.title)
        assertEquals(listOf("quick", "easy"), derived.recipe.tags)
        assertEquals("Crisp.", derived.recipe.tagline)
        assertEquals(listOf(StepKind.STEP, StepKind.TIP), derived.recipe.steps.map { it.kind }, "method first, blank steps dropped")
        assertEquals("~2 min", derived.recipe.steps.first().time)

        val fixed = derive(edit.copy(servesText = "3", prepText = "10", title = ""))
        assertTrue(fixed.titleMissing)
        assertEquals(listOf("Give the recipe a title."), fixed.problems)
        assertTrue(derive(edit.copy(servesText = "3", prepText = "10")).canSave)
    }

    @Test
    fun blankLinesAreLeftOutOfTheSavedRecipeAndTheCounts() {
        var edit = ReviewEdit(title = "Empty")
        edit = ReviewRules.addLine(edit)
        edit = ReviewRules.addLine(edit)
        val derived = derive(edit)
        assertTrue(derived.recipe.lines.isEmpty())
        assertEquals(0, derived.unmatched)
        assertTrue(derived.canSave, "a recipe may be saved with no ingredients")
    }
}
