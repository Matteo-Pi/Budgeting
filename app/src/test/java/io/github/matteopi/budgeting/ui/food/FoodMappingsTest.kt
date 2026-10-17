package io.github.matteopi.budgeting.ui.food

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.ui.food.components.FoodBook
import io.github.matteopi.budgeting.ui.food.recipe.RecipeDetailMapping
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Recipes tab's cards, the Compare table and the recipe screen's lines, on the starter book with its typed
 * prices: the numbers are the §11.7 checkpoint ones (docs/PLAN.md).
 */
class FoodMappingsTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val models = RecipeBundle.starter().toModels()
    private val ingredients = models.ingredients.associateBy { it.id }

    /** The four recipes, created a day apart in book order so "newest" is the chilli. */
    private val recipes: List<Recipe> = models.recipes.mapIndexed { index, recipe ->
        recipe.copy(id = index + 1L, createdAtEpochMillis = 1_000L * (index + 1))
    }

    private fun book(countOptional: Boolean = true, list: List<Recipe> = recipes) = FoodBook(
        recipes = list,
        ingredients = ingredients,
        prices = Prices.resolveAll(models.ingredients, emptyList(), today),
        countOptionalExtras = countOptional,
        today = today,
    )

    private fun recipe(short: String): Recipe = recipes.first { it.shortTitle == short }

    @Test
    fun eachCardHasItsRecipesNumbersAtItsOwnServings() {
        val cards = RecipeListMapping.cards(book()).associateBy { it.title }
        assertEquals(setOf("Stir-fry", "Traybake", "Braise", "Chilli"), cards.keys)
        val stirFry = cards.getValue("Stir-fry")
        assertEquals(Money(309), stirFry.cost)
        assertFalse(stirFry.costAtLeast)
        assertEquals(804, Math.round(stirFry.kcal))
        assertEquals(5.84, stirFry.salt, 0.005)
        assertEquals(Money(254), cards.getValue("Chilli").cost)
        assertEquals(Money(383), cards.getValue("Traybake").cost)
    }

    @Test
    fun withoutOptionalExtrasTheTraybakeCostsLess() {
        val traybake = RecipeListMapping.cards(book(countOptional = false)).first { it.title == "Traybake" }
        assertEquals(Money(305), traybake.cost)
    }

    @Test
    fun archivedRecipesHaveNoCard() {
        val list = recipes.map { if (it.shortTitle == "Braise") it.copy(archived = true) else it }
        assertEquals(3, RecipeListMapping.cards(book(list = list)).size)
    }

    @Test
    fun cardsSortAndFilter() {
        val cards = RecipeListMapping.cards(book())
        assertEquals("Chilli", RecipeListMapping.sorted(cards, RecipeSort.COST).first().title)
        assertEquals("Traybake", RecipeListMapping.sorted(cards, RecipeSort.COST).last().title)
        assertEquals("Chilli", RecipeListMapping.sorted(cards, RecipeSort.NEWEST).first().title)
        assertEquals("Braise", RecipeListMapping.sorted(cards, RecipeSort.PROTEIN).first().title)
        assertEquals(listOf("Chilli"), RecipeListMapping.filter(cards, "beef", emptySet()).map { it.title })
        assertEquals(setOf("Stir-fry", "Braise", "Chilli"), RecipeListMapping.filter(cards, "", setOf("rice")).map { it.title }.toSet())
        assertTrue("chicken" in RecipeListMapping.allTags(cards).take(2))
    }

    @Test
    fun compareSortsByAColumnAndMarksTheBest() {
        val rows = CompareMapping.rows(book())
        val byCost = CompareMapping.sorted(rows, CompareMapping.tapped(CompareSort(), CompareColumn.COST))
        assertEquals(listOf("Chilli", "Stir-fry", "Braise", "Traybake"), byCost.map { it.title })
        val byProtein = CompareMapping.sorted(rows, CompareMapping.tapped(CompareSort(), CompareColumn.PROTEIN))
        assertEquals("Braise", byProtein.first().title)
        val best = CompareMapping.bestByColumn(rows)
        assertEquals(setOf(recipe("Chilli").id), best.getValue(CompareColumn.COST))
        assertEquals(setOf(recipe("Chilli").id), best.getValue(CompareColumn.SALT))
        assertEquals(setOf(recipe("Stir-fry").id), best.getValue(CompareColumn.VEG))
    }

    @Test
    fun theRecipeScreenScalesTheLinesToTheServings() {
        val chilli = recipe("Chilli")
        val own = RecipeDetailMapping.lines(chilli, ingredients, chilli.serves)
        assertEquals("500 g lean beef mince (5% fat)", own.first().text)
        val forSix = RecipeDetailMapping.lines(chilli, ingredients, 6)
        assertEquals("750 g lean beef mince (5% fat)", forSix.first().text)
        assertEquals(chilli.lines.size, forSix.size)
        // The water line has no ingredient: it is shown but doesn't count.
        assertFalse(forSix.first { "boiling water" in it.text }.counted)
        assertTrue(RecipeDetailMapping.hasPulses(chilli, ingredients))
        assertFalse(RecipeDetailMapping.hasPulses(recipe("Stir-fry"), ingredients))
        // The cost of a portion doesn't change with the servings.
        assertEquals(Money(254), book().analyse(chilli, 6).cost.perPortion)
    }
}
