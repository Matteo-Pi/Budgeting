package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientAliasRecord
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeLine
import io.github.matteopi.budgeting.core.recipes.RecipeSource
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.ShoppingEntry
import io.github.matteopi.budgeting.core.recipes.ShoppingMark
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.data.db.dao.IngredientWithPortions
import io.github.matteopi.budgeting.data.db.dao.RecipeWithParts
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Entity <-> core model round trips for the food tables of docs/PLAN.md §11.3. */
class FoodMappersTest {
    private val onion = Ingredient(
        id = 7,
        name = "Brown onions",
        unit = BaseUnit.G,
        packSize = 1_000.0,
        packLabel = "1 kg",
        price = Money(95),
        priceDate = LocalDate.of(2026, 10, 15),
        store = "Tesco",
        productId = 3,
        productName = "Tesco Brown Onions 1kg",
        sourceUrl = "https://example.invalid/onions",
        barcode = "5000000000001",
        per100 = Nutrition(kcal = 36.0, fat = 0.1, saturates = 0.0, carbs = 7.9, sugars = 5.6, fibre = 1.4, protein = 0.9, salt = 0.01),
        nutritionSource = NutritionSource.BARCODE,
        vegKind = VegKind.VEG,
        shelf = Shelf.FRUIT_VEG,
        note = "Loose is cheaper",
        archived = true,
        createdAtEpochMillis = 1_760_000_000_000,
        portions = listOf(Portion("each", 150.0), Portion("small", 90.5), Portion("large", 220.0)),
    )

    @Test
    fun ingredientWithEveryFieldSetRoundTripsThroughItsRows() {
        val entity = onion.toEntity()
        val portions = onion.toPortionEntities().mapIndexed { index, row -> row.copy(id = index + 1L) }

        assertEquals(onion.price?.pence, entity.price)
        assertEquals(LocalDate.of(2026, 10, 15).toEpochDay(), entity.priceDate)
        assertEquals("G", entity.unit)
        assertEquals("BARCODE", entity.nutritionSource)
        assertEquals("VEG", entity.vegKind)
        assertEquals("FRUIT_VEG", entity.shelf)
        assertEquals(36.0, entity.kcal)
        assertEquals(0.01, entity.salt)
        assertEquals(listOf(7L, 7L, 7L), portions.map { it.ingredientId })
        assertEquals(onion, entity.toModel(portions))
    }

    @Test
    fun portionsComeBackInTheOrderTheyWereSavedNotInTheOrderReadBack() {
        val portions = onion.toPortionEntities().mapIndexed { index, row -> row.copy(id = index + 1L) }

        val shuffled = IngredientWithPortions(onion.toEntity(), portions.reversed())

        assertEquals(onion.portions, shuffled.toModel().portions)
    }

    @Test
    fun anIngredientWithNothingKnownKeepsItsNullsAsNulls() {
        val bare = Ingredient(id = 1, name = "Mystery", packSize = 1.0)

        val entity = bare.toEntity()

        assertNull(entity.price)
        assertNull(entity.priceDate)
        assertNull(entity.productId)
        assertNull(entity.barcode)
        assertNull(entity.kcal)
        assertNull(entity.salt)
        assertEquals(bare, entity.toModel())
        assertEquals(Nutrition(), entity.toModel().per100)
    }

    @Test
    fun anEnumNameThisVersionDoesNotKnowReadsAsItsDefault() {
        val entity = onion.toEntity().copy(unit = "TBSP", nutritionSource = "PSYCHIC", vegKind = "FRUITY", shelf = "ABROAD")

        val model = entity.toModel()

        assertEquals(BaseUnit.G, model.unit)
        assertEquals(NutritionSource.TYPED, model.nutritionSource)
        assertEquals(VegKind.NONE, model.vegKind)
        assertEquals(Shelf.OTHER, model.shelf)
    }

    @Test
    fun ingredientAlias() {
        val model = IngredientAliasRecord(id = 4, nameKey = "spanish onion", ingredientId = 7)
        assertEquals(model, model.toEntity().toModel())
    }

    private val soup = Recipe(
        id = 5,
        title = "Onion soup",
        shortTitle = "Soup",
        tagline = "Cheap and warm",
        style = "Hob · one pot",
        serves = 4,
        prepMins = 10,
        cookMins = 40,
        timeNote = "plus cooling",
        servedWith = "Bread",
        tags = listOf("Quick", "Veggie"),
        rating = 4,
        notes = "Add more pepper.",
        source = RecipeSource.CLAUDE,
        createdAtEpochMillis = 111,
        updatedAtEpochMillis = 222,
        archived = true,
        lines = listOf(
            RecipeLine(group = "Soup", text = "4 onions, sliced", ingredientId = 7, quantity = 600.0),
            RecipeLine(text = "1 stock cube", ingredientId = 8, quantity = 10.0, optional = true),
            RecipeLine(text = "salt to taste"),
        ),
        steps = listOf(
            RecipeStep(StepKind.STEP, "Soften", "~10 min", "Cook the onions."),
            RecipeStep(StepKind.TIP, body = "Salt early."),
            RecipeStep(StepKind.STEP, body = "Add the stock."),
            RecipeStep(StepKind.STORAGE, body = "Fridge 3 days."),
            RecipeStep(StepKind.EQUIPMENT, body = "A big pan."),
        ),
    )

    @Test
    fun recipeRoundTripsThroughItsThreeTables() {
        val lines = soup.toLineEntities().mapIndexed { index, row -> row.copy(id = index + 1L) }
        val steps = soup.toStepEntities().mapIndexed { index, row -> row.copy(id = index + 1L) }

        assertEquals(listOf(0, 1, 2), lines.map { it.position })
        assertEquals(listOf(0, 1, 2, 3, 4), steps.map { it.position })
        assertEquals(listOf(5L, 5L, 5L), lines.map { it.recipeId })
        assertEquals("Soup", lines[0].grp)
        assertEquals("CLAUDE", soup.toEntity().source)
        assertEquals("Quick,Veggie", soup.toEntity().tags)
        assertEquals(soup, soup.toEntity().toModel(lines, steps))
    }

    @Test
    fun linesAndStepsAreReadBackInPositionOrderWhateverTheRowOrder() {
        val lines = soup.toLineEntities().mapIndexed { index, row -> row.copy(id = index + 1L) }
        val steps = soup.toStepEntities().mapIndexed { index, row -> row.copy(id = index + 1L) }

        val read = RecipeWithParts(soup.toEntity(), lines.reversed(), steps.shuffledDeterministically()).toModel()

        assertEquals(soup.lines, read.lines)
        assertEquals(soup.steps, read.steps)
    }

    private fun <T> List<T>.shuffledDeterministically(): List<T> = filterIndexed { i, _ -> i % 2 == 0 } + filterIndexed { i, _ -> i % 2 == 1 }

    @Test
    fun tagsAreStoredCommaSeparatedTrimmedWithBlanksDropped() {
        val entity = soup.copy(tags = listOf(" Quick ", "", "a, b", "  ")).toEntity()

        assertEquals("Quick,a,b", entity.tags)
        assertEquals(listOf("Quick", "a", "b"), entity.toModel().tags)
        assertEquals(emptyList(), soup.copy(tags = emptyList()).toEntity().toModel().tags)
        assertEquals("", soup.copy(tags = emptyList()).toEntity().tags)
    }

    @Test
    fun aRecipeWithNothingOptionalKeepsItsNulls() {
        val plain = Recipe(id = 2, title = "Toast")

        val entity = plain.toEntity()

        assertNull(entity.shortTitle)
        assertNull(entity.prepMins)
        assertNull(entity.cookMins)
        assertEquals(plain, entity.toModel())
        assertEquals(emptyList(), plain.toLineEntities())
        assertEquals(emptyList(), plain.toStepEntities())
    }

    @Test
    fun anUnknownRecipeSourceOrStepKindReadsAsItsDefault() {
        val entity = soup.toEntity().copy(source = "ROBOT")
        val step = soup.toStepEntities().first().copy(kind = "RIDDLE")

        assertEquals(RecipeSource.MINE, entity.toModel().source)
        assertEquals(StepKind.STEP, step.toModel().kind)
    }

    @Test
    fun shoppingEntries() {
        val recipeEntry = ShoppingEntry(id = 1, recipeId = 5, servings = 8, addedAtEpochMillis = 100)
        val extra = ShoppingEntry(id = 2, ingredientId = 7, quantity = 250.0, addedAtEpochMillis = 101)
        val text = ShoppingEntry(id = 3, freeText = "Bin bags", checked = true, addedAtEpochMillis = 102)

        for (model in listOf(recipeEntry, extra, text)) {
            assertEquals(model, model.toEntity().toModel())
        }
        assertNull(extra.toEntity().recipeId)
        assertNull(recipeEntry.toEntity().freeText)
    }

    @Test
    fun shoppingMarks() {
        val ticked = ShoppingMark(ingredientId = 7, checked = true)
        val haveIt = ShoppingMark(ingredientId = 8, haveIt = true)

        assertEquals(ticked, ticked.toEntity().toModel())
        assertEquals(haveIt, haveIt.toEntity().toModel())
        assertEquals(0L, ticked.toEntity().id, "a new row gets its id from the database")
    }
}
