package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientMatching
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.core.recipes.MergeChoice
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.Prices
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.core.recipes.RecipeLine
import io.github.matteopi.budgeting.core.recipes.RecipeMaths
import io.github.matteopi.budgeting.core.recipes.RecipeNumbers
import io.github.matteopi.budgeting.core.recipes.RecipeSource
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.ShoppingList
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.core.recipes.TrafficLights
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.testing.DataTestSupport
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Recipes section's data layer against the real database (docs/PLAN.md §11): the starter recipe
 * book imported through [io.github.matteopi.budgeting.data.repo.RecipeRepository] and read back
 * through the repositories gives the §11.7 checkpoint numbers; the merge rules, archiving, cascades
 * and the shopping list behave as the plan says.
 *
 * Prices are the typed pack prices of the starter book (no receipts), and "today" is fixed at
 * 17 October 2026 so no price counts as old.
 */
@RunWith(AndroidJUnit4::class)
class FoodRepositoryTest {
    private val container get() = DataTestSupport.container
    private val ingredients get() = container.ingredientRepository
    private val recipes get() = container.recipeRepository
    private val shopping get() = container.shoppingRepository

    private val today: LocalDate = LocalDate.of(2026, 10, 17)

    private val stirFryTitle = "Chicken & Vegetable Stir-Fry"
    private val chilliTitle = "Beef, Bean & Lentil Chilli"

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Three ingredients of the user's own before anything is imported, so the real ids of the book's
     * ingredients are not the provisional ids 1 to 40 its recipe lines are written with. A missing
     * remap would then show up in the numbers instead of passing by luck.
     */
    private suspend fun saveOwnIngredients() {
        for (name in listOf("Kitchen roll", "Foil", "Baking paper")) {
            ingredients.save(Ingredient(name = name, unit = BaseUnit.G, packSize = 10.0))
        }
    }

    private suspend fun allIngredients(): List<Ingredient> = ingredients.observeAll().first()

    private suspend fun allRecipes(): List<Recipe> = recipes.observeAll().first()

    private suspend fun ingredientNamed(name: String): Ingredient =
        allIngredients().first { it.name == name }

    private suspend fun recipeTitled(title: String): Recipe = allRecipes().first { it.title == title }

    private suspend fun numbersOf(recipe: Recipe, countOptional: Boolean): RecipeNumbers {
        val all = allIngredients()
        val prices = Prices.resolveAll(all, emptyList(), today)
        return RecipeMaths.analyse(recipe, all.associateBy { it.id }, prices, recipe.serves, countOptional)
    }

    private fun count(sql: String): Long =
        container.database.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun letter(light: Light?): Char = when (light) {
        Light.GREEN -> 'G'
        Light.AMBER -> 'A'
        Light.RED -> 'R'
        null -> '?'
    }

    private class Row(
        val title: String,
        val optional: Boolean,
        val perPortion: Long,
        val whole: Long,
        val cupboard: Long,
        val kcal: Long,
        val protein: Double,
        val salt: Double,
        val veg: Double,
        val colours: String,
    )

    /** The checkpoint table of docs/PLAN.md §11.7. */
    private val checkpoints = listOf(
        Row("Chicken & Vegetable Stir-Fry", true, 309, 618, 2056, 804, 56.8, 5.84, 6.06, "RGGR"),
        Row("Chicken & Vegetable Stir-Fry", false, 301, 601, 1891, 793, 56.7, 5.12, 6.06, "RGGR"),
        Row("Chicken, Potato & Vegetable Traybake", true, 383, 765, 2310, 768, 54.9, 2.61, 5.75, "RGGR"),
        Row("Chicken, Potato & Vegetable Traybake", false, 305, 610, 2025, 734, 52.6, 2.59, 4.81, "RGGR"),
        Row("Chicken Thigh, Bacon & Vegetable Braise", true, 372, 745, 2932, 891, 57.0, 5.14, 3.81, "RRGR"),
        Row("Chicken Thigh, Bacon & Vegetable Braise", false, 364, 729, 2773, 857, 56.5, 5.13, 2.81, "RRGR"),
        Row("Beef, Bean & Lentil Chilli", true, 254, 1017, 3178, 897, 54.0, 1.78, 5.88, "GGGG"),
        Row("Beef, Bean & Lentil Chilli", false, 253, 1011, 2898, 897, 54.0, 1.41, 5.88, "GGGG"),
    )

    private suspend fun assertCheckpoints(rows: List<Row>) {
        for (row in rows) {
            val label = "${row.title} (optional extras ${if (row.optional) "on" else "off"})"
            val n = numbersOf(recipeTitled(row.title), row.optional)
            assertEquals(Money(row.perPortion), n.cost.perPortion, "$label per portion")
            assertEquals(Money(row.whole), n.cost.whole, "$label whole dish")
            assertEquals(Money(row.cupboard), n.cost.fromEmptyCupboard, "$label from an empty cupboard")
            assertEquals(row.kcal, Math.round(n.nutrition.perPortion.kcal), "$label kcal")
            assertEquals(row.protein, n.nutrition.perPortion.protein, 0.05, "$label protein")
            assertEquals(row.salt, n.nutrition.perPortion.salt, 0.005, "$label salt")
            assertEquals(row.veg, n.veg, 0.005, "$label veg")
            val colours = TrafficLights.coloured.map { letter(n.nutrition.lights[it]) }.joinToString("")
            assertEquals(row.colours, colours, "$label colours (fat, saturates, sugars, salt)")
            assertTrue(n.cost.missingPrice.isEmpty(), "$label: every ingredient has a price")
            assertTrue(n.cost.oldPrice.isEmpty(), "$label: no price is old")
            assertFalse(n.nutrition.atLeast, "$label: every nutrition value is known")
        }
    }

    // ---- the starter recipe book --------------------------------------------------------------

    @Test
    fun theStarterBookIsInsideTheApk() {
        val bundle = RecipeBundle.starter()
        assertEquals(4, bundle.recipes.size)
        assertEquals(40, bundle.ingredients.size)
    }

    @Test
    fun importingTheStarterBookGivesTheCheckpointNumbersThroughTheRepositories() = runBlocking<Unit> {
        saveOwnIngredients()

        val result = recipes.importBundle(RecipeBundle.starter())

        assertEquals(4, result.recipeIds.size)
        assertEquals(40, result.ingredientsAdded)
        assertEquals(0, result.ingredientsReused)
        assertEquals(43, allIngredients().size, "the book's forty and the user's three")
        assertEquals(4, allRecipes().size)
        val provisionalSalt = RecipeBundle.starter().toModels().ingredientKeys.getValue("salt")
        assertTrue(ingredientNamed("Salt").id != provisionalSalt, "the real ids are not the bundle's provisional ones")
        assertTrue(allIngredients().all { it.createdAtEpochMillis > 0 }, "every ingredient has a creation time")

        assertCheckpoints(checkpoints)
    }

    @Test
    fun theStirFryAndTheChilliKeepTheirLinesStepsAndDetailsInOrder() = runBlocking<Unit> {
        val bundle = RecipeBundle.starter()
        recipes.importBundle(bundle)

        for (source in bundle.recipes) {
            val saved = recipeTitled(source.title)
            assertEquals(source.serves, saved.serves, "${source.title} serves")
            assertEquals(source.lines.map { it.text }, saved.lines.map { it.text }, "${source.title} lines in order")
            assertEquals(source.lines.map { it.group }, saved.lines.map { it.group }, "${source.title} groups")
            assertEquals(source.lines.map { it.optional }, saved.lines.map { it.optional }, "${source.title} optional flags")
            assertEquals(source.steps.map { it.body }, saved.method.map { it.body }, "${source.title} method in order")
            assertEquals(source.steps.map { it.title }, saved.method.map { it.title }, "${source.title} step titles")
            assertEquals(source.steps.map { it.time }, saved.method.map { it.time }, "${source.title} step times")
            assertEquals(source.tips, saved.tips.map { it.body }, "${source.title} tips")
            assertEquals(source.equipment, saved.equipment.map { it.body }, "${source.title} equipment")
            assertEquals(source.storage, saved.storage.map { it.body }, "${source.title} storage")
            assertEquals(source.tags, saved.tags, "${source.title} tags")
            assertEquals(RecipeSource.CLAUDE, saved.source)
            assertTrue(saved.createdAtEpochMillis > 0, "${source.title} keeps the date it was added")
            assertFalse(saved.archived)
        }
        val stirFry = recipeTitled(stirFryTitle)
        assertEquals(
            listOf(StepKind.STEP, StepKind.TIP, StepKind.EQUIPMENT),
            stirFry.steps.map { it.kind }.distinct(),
            "method first, then tips, then equipment, as the bundle lists them",
        )
        val known = allIngredients().map { it.id }.toSet()
        assertTrue(stirFry.lines.all { line -> line.ingredientId == null || line.ingredientId in known })
    }

    @Test
    fun theStarterIngredientsKeepTheirNutritionPackPricePortionsAndUnknownValues() = runBlocking<Unit> {
        val bundle = RecipeBundle.starter()
        recipes.importBundle(bundle)
        val models = bundle.toModels()

        for (source in models.ingredients) {
            val saved = ingredientNamed(source.name)
            assertEquals(source.copy(id = saved.id, createdAtEpochMillis = saved.createdAtEpochMillis), saved, source.name)
        }
        val salt = ingredientNamed("Salt")
        assertNotNull(salt.price)
        assertNotNull(salt.priceDate)
        assertEquals(models.ingredients.count { it.portions.isNotEmpty() }, allIngredients().count { it.portions.isNotEmpty() })
    }

    @Test
    fun importingTheBookAgainWithUseExistingAddsFourRecipesAndNoIngredients() = runBlocking<Unit> {
        recipes.importBundle(RecipeBundle.starter())
        val firstIds = allIngredients().map { it.id }.toSet()
        val aliasesBefore = ingredients.aliasMap().size

        val matches = recipes.findMatches(RecipeBundle.starter())
        assertEquals(40, matches.size, "every ingredient of the book is already there")

        val again = recipes.importBundle(RecipeBundle.starter())

        assertEquals(4, again.recipeIds.size)
        assertEquals(0, again.ingredientsAdded)
        assertEquals(40, again.ingredientsReused)
        assertEquals(8, allRecipes().size)
        assertEquals(firstIds, allIngredients().map { it.id }.toSet(), "no ingredient was added")
        assertEquals(aliasesBefore, ingredients.aliasMap().size, "no alias was added: every name is taken")

        // The second copies cost the same: their lines point at the ingredients that were already there.
        val copies = allRecipes().filter { it.id in again.recipeIds }
        assertEquals(4, copies.size)
        for (copy in copies) {
            val original = allRecipes().first { it.title == copy.title && it.id !in again.recipeIds }
            assertEquals(original.lines.map { it.ingredientId }, copy.lines.map { it.ingredientId }, "${copy.title} lines")
            assertEquals(numbersOf(original, true).cost.perPortion, numbersOf(copy, true).cost.perPortion, "${copy.title} per portion")
        }
    }

    @Test
    fun importAsNewAddsThatIngredientAndPointsTheNewRecipesAtIt() = runBlocking<Unit> {
        val bundle = RecipeBundle.starter()
        recipes.importBundle(bundle)
        val oldSalt = ingredientNamed("Salt")

        val again = recipes.importBundle(bundle, mapOf("salt" to MergeChoice.IMPORT_AS_NEW))

        assertEquals(1, again.ingredientsAdded)
        assertEquals(39, again.ingredientsReused)
        assertEquals(41, allIngredients().size)
        val newSalt = allIngredients().single { it.name == "Salt" && it.id != oldSalt.id }

        val models = bundle.toModels()
        val provisionalSalt = models.ingredientKeys.getValue("salt")
        var saltLines = 0
        for (source in models.recipes) {
            val copy = allRecipes().first { it.title == source.title && it.id in again.recipeIds }
            val first = allRecipes().first { it.title == source.title && it.id !in again.recipeIds }
            source.lines.forEachIndexed { index, line ->
                if (line.ingredientId == provisionalSalt) {
                    saltLines++
                    assertEquals(newSalt.id, copy.lines[index].ingredientId, "${source.title} line ${index + 1} uses the new salt")
                    assertEquals(oldSalt.id, first.lines[index].ingredientId, "the first import still uses the old salt")
                } else {
                    assertEquals(first.lines[index].ingredientId, copy.lines[index].ingredientId, "${source.title} line ${index + 1}")
                }
            }
        }
        assertTrue(saltLines > 0, "the book has lines that use salt")
    }

    @Test
    fun anExistingIngredientIsMatchedByANameOrALearnedAliasAndTheBundleIsRemappedOnToIt() = runBlocking<Unit> {
        // The user's own ingredients, named differently from the book's.
        val myOil = ingredients.save(Ingredient(name = "Rapeseed oil", unit = BaseUnit.ML, packSize = 1_000.0, price = Money(250)))
        val myGarlic = ingredients.save(Ingredient(name = "garlic", unit = BaseUnit.G, packSize = 60.0, price = Money(70)))
        assertTrue(ingredients.learnAlias("Vegetable oil (rapeseed)", myOil), "the book's name for it is learned")

        val bundle = RecipeBundle.starter()
        val matches = recipes.findMatches(bundle).associate { it.key to it.existing.id }
        assertEquals(myOil, matches["veg_oil"], "by learned alias")
        assertEquals(myGarlic, matches["garlic"], "by name, whatever the case")
        assertEquals(2, matches.size)

        val result = recipes.importBundle(bundle)

        assertEquals(2, result.ingredientsReused)
        assertEquals(38, result.ingredientsAdded)
        assertEquals(40, allIngredients().size, "two of the user's own plus 38 new")
        val models = bundle.toModels()
        val provisionalOil = models.ingredientKeys.getValue("veg_oil")
        val provisionalGarlic = models.ingredientKeys.getValue("garlic")
        var oilLines = 0
        var garlicLines = 0
        for (source in models.recipes) {
            val saved = recipeTitled(source.title)
            source.lines.forEachIndexed { index, line ->
                when (line.ingredientId) {
                    provisionalOil -> {
                        oilLines++
                        assertEquals(myOil, saved.lines[index].ingredientId, "${source.title}: the oil line uses the user's oil")
                    }
                    provisionalGarlic -> {
                        garlicLines++
                        assertEquals(myGarlic, saved.lines[index].ingredientId, "${source.title}: the garlic line uses the user's garlic")
                    }
                    else -> Unit
                }
            }
        }
        assertTrue(oilLines >= 1 && garlicLines >= 4, "the book has $oilLines oil lines and $garlicLines garlic lines")
        assertEquals(myOil, ingredients.aliasMap()[IngredientMatching.nameKey("Vegetable oil (rapeseed)")], "an alias that was taken is not replaced")
    }

    @Test
    fun anArchivedIngredientIsNotMatchedByAnImport() = runBlocking<Unit> {
        val old = ingredients.save(Ingredient(name = "Salt", unit = BaseUnit.G, packSize = 750.0))
        ingredients.setArchived(old, true)

        val bundle = RecipeBundle.starter()
        assertTrue(recipes.findMatches(bundle).isEmpty())
        val result = recipes.importBundle(bundle)

        assertEquals(40, result.ingredientsAdded)
        assertEquals(2, allIngredients().count { it.name == "Salt" })
    }

    // ---- ingredients ----------------------------------------------------------------------------

    @Test
    fun archivingAnIngredientUsedByALineKeepsTheLineAndTheNumbers() = runBlocking<Unit> {
        recipes.importBundle(RecipeBundle.starter())
        val chicken = ingredientNamed("Chicken breast fillets")
        val before = numbersOf(recipeTitled(stirFryTitle), true)

        ingredients.setArchived(chicken.id, true)

        val stirFry = recipeTitled(stirFryTitle)
        assertTrue(stirFry.lines.any { it.ingredientId == chicken.id }, "the line still points at the archived ingredient")
        assertEquals(true, ingredients.get(chicken.id)?.archived)
        assertTrue(allIngredients().any { it.id == chicken.id && it.archived }, "the list still has it, marked archived")
        assertEquals(before.cost.perPortion, numbersOf(stirFry, true).cost.perPortion)
        assertFalse(ingredients.deleteIfUnused(chicken.id), "an ingredient a line points at is never deleted")
        assertNotNull(ingredients.get(chicken.id))

        ingredients.setArchived(chicken.id, false)
        assertEquals(false, ingredients.get(chicken.id)?.archived)
    }

    @Test
    fun anUnusedIngredientCanBeDeletedWithItsPortionsAliasesAndShoppingEntries() = runBlocking<Unit> {
        val id = ingredients.save(Ingredient(name = "Foil", unit = BaseUnit.G, packSize = 10.0, portions = listOf(Portion("sheet", 1.0))))
        ingredients.learnAlias("Tin foil", id)
        shopping.addIngredient(id, 5.0)
        shopping.setChecked(id, true)
        assertEquals(1L, count("SELECT COUNT(*) FROM ingredient_portions"))

        assertTrue(ingredients.deleteIfUnused(id))

        assertNull(ingredients.get(id))
        assertEquals(0L, count("SELECT COUNT(*) FROM ingredient_portions"))
        assertEquals(0L, count("SELECT COUNT(*) FROM ingredient_aliases"))
        assertEquals(0L, count("SELECT COUNT(*) FROM shopping_entries"))
        assertEquals(0L, count("SELECT COUNT(*) FROM shopping_marks"))
    }

    @Test
    fun anIngredientIsTidiedOnSaveAndKeepsEverythingElseAsGiven() = runBlocking<Unit> {
        val id = ingredients.save(
            Ingredient(
                name = "  Brown onions ", unit = BaseUnit.G, packSize = 1_000.0, packLabel = " 1 kg ",
                price = Money(95), priceDate = LocalDate.of(2026, 10, 15), store = "Tesco", productName = "  ",
                sourceUrl = null, barcode = " ", per100 = Nutrition(kcal = 36.0, protein = 0.9),
                nutritionSource = NutritionSource.LABEL, vegKind = VegKind.VEG, shelf = Shelf.FRUIT_VEG, note = " Brown ",
                portions = listOf(Portion("each", 150.0), Portion("Each", 1.0), Portion(" ", 5.0), Portion("small", 0.0), Portion("large", 200.0)),
            ),
        )

        val saved = checkNotNull(ingredients.get(id))
        assertEquals("Brown onions", saved.name)
        assertEquals("1 kg", saved.packLabel)
        assertEquals(Money(95), saved.price)
        assertEquals(LocalDate.of(2026, 10, 15), saved.priceDate)
        assertEquals("Tesco", saved.store)
        assertNull(saved.productName)
        assertNull(saved.barcode)
        assertEquals("Brown", saved.note)
        assertEquals(Nutrition(kcal = 36.0, protein = 0.9), saved.per100, "unknown values stay unknown, not zero")
        assertEquals(NutritionSource.LABEL, saved.nutritionSource)
        assertEquals(VegKind.VEG, saved.vegKind)
        assertEquals(Shelf.FRUIT_VEG, saved.shelf)
        assertEquals(listOf(Portion("each", 150.0), Portion("large", 200.0)), saved.portions)
        assertTrue(saved.createdAtEpochMillis > 0)

        // Saving again with the same id updates it in place: new price, other portions, same creation time.
        ingredients.save(saved.copy(price = Money(99), portions = listOf(Portion("each", 160.0))))
        val updated = checkNotNull(ingredients.get(id))
        assertEquals(Money(99), updated.price)
        assertEquals(listOf(Portion("each", 160.0)), updated.portions)
        assertEquals(saved.createdAtEpochMillis, updated.createdAtEpochMillis)
        assertEquals(1, allIngredients().size)

        assertFailsWith<IllegalArgumentException> { ingredients.save(Ingredient(name = "  ")) }
        assertFailsWith<IllegalArgumentException> { ingredients.save(Ingredient(name = "Nothing", packSize = 0.0)) }
        assertFailsWith<IllegalArgumentException> { ingredients.save(saved.copy(id = 9_999)) }
    }

    @Test
    fun aBarcodeFindsItsIngredientAndCannotBeUsedTwice() = runBlocking<Unit> {
        val id = ingredients.save(Ingredient(name = "Beans", barcode = "5012345678900"))
        ingredients.save(Ingredient(name = "No barcode one"))
        ingredients.save(Ingredient(name = "No barcode two"))

        assertEquals(id, ingredients.findByBarcode("5012345678900")?.id)
        assertNull(ingredients.findByBarcode("0000000000000"))
        assertFailsWith<Exception> { ingredients.save(Ingredient(name = "Copy", barcode = "5012345678900")) }
        assertEquals(3, allIngredients().size)
    }

    @Test
    fun learnedNamesMapToIngredientsAndCanBeReplacedOrRemoved() = runBlocking<Unit> {
        val onion = ingredients.save(Ingredient(name = "Brown onions"))
        val shallot = ingredients.save(Ingredient(name = "Shallots"))
        val key = IngredientMatching.nameKey("Spanish onions")

        assertTrue(ingredients.learnAlias("Spanish onions", onion))
        assertFalse(ingredients.learnAlias("spanish onion", shallot), "the same name once normalised is already taken")
        assertEquals(onion, ingredients.aliasMap()[key])
        assertFalse(ingredients.learnAlias("   ", onion), "a blank name teaches nothing")

        ingredients.mapAlias("Spanish onions", shallot)
        assertEquals(shallot, ingredients.aliasMap()[key], "the user's choice replaces what it meant")
        assertEquals(1, ingredients.observeAliases().first().size)

        ingredients.mapAlias("Red onions", onion)
        assertEquals(2, ingredients.aliasMap().size)

        val record = ingredients.observeAliases().first().single { it.nameKey == key }
        ingredients.removeAlias(record.id)
        assertNull(ingredients.aliasMap()[key])
        assertEquals(onion, ingredients.aliasMap()[IngredientMatching.nameKey("Red onions")])
    }

    @Test
    fun anIngredientCanBeLinkedToAProductAndTheLinkIsClearedIfTheProductGoes() = runBlocking<Unit> {
        val productId = container.receiptRepository.learnProduct("TESCO", "ts onions 1kg", "Onions 1kg", null)
        val id = ingredients.save(Ingredient(name = "Brown onions", packSize = 1_000.0))
        assertNull(ingredients.get(id)?.productId)

        ingredients.setProduct(id, productId)
        assertEquals(productId, ingredients.get(id)?.productId)
        assertEquals(productId, ingredients.observeAll().first().single().productId)

        ingredients.setProduct(id, null)
        assertNull(ingredients.get(id)?.productId)

        ingredients.setProduct(id, productId)
        container.database.receiptDao().deleteAllProducts()
        assertNull(ingredients.get(id)?.productId, "deleting the product clears the link and keeps the ingredient")
        assertNotNull(ingredients.get(id))
    }

    // ---- recipes --------------------------------------------------------------------------------

    @Test
    fun aRecipeIsSavedWithItsLinesAndStepsInOrderAndReplacedInPlace() = runBlocking<Unit> {
        val saltId = ingredients.save(Ingredient(name = "Salt", packSize = 750.0, packLabel = "750 g", price = Money(65), shelf = Shelf.CUPBOARD))
        val toast = Recipe(
            title = " Toast ", shortTitle = "  ", serves = 0, tags = listOf(" Quick ", "", "a, b"), rating = 9,
            lines = listOf(
                RecipeLine(text = "2 slices of bread"),
                RecipeLine(group = "Seasoning", text = "a pinch of salt", ingredientId = saltId, quantity = 0.3, optional = true),
            ),
            steps = listOf(
                RecipeStep(StepKind.TIP, body = "Use day-old bread."),
                RecipeStep(StepKind.STEP, "Toast", "~3 min", "Toast it."),
                RecipeStep(StepKind.STORAGE, body = "Eat now."),
                RecipeStep(StepKind.STEP, body = "Butter it."),
            ),
        )

        val id = recipes.save(toast)

        val saved = checkNotNull(recipes.get(id))
        assertEquals("Toast", saved.title)
        assertNull(saved.shortTitle)
        assertEquals(1, saved.serves)
        assertEquals(5, saved.rating)
        assertEquals(listOf("Quick", "a", "b"), saved.tags)
        assertEquals(toast.lines, saved.lines)
        assertEquals(toast.steps, saved.steps, "tips, steps and storage keep the order they were given in")
        assertEquals(listOf("Toast", "Butter it."), saved.method.map { it.title ?: it.body })
        assertTrue(saved.createdAtEpochMillis > 0)
        assertEquals(saved.createdAtEpochMillis, saved.updatedAtEpochMillis)

        // A shopping entry for it, then a replace: the lines and steps are swapped, the entry survives.
        val entry = shopping.addRecipe(id, 3)
        recipes.save(saved.copy(title = "Better toast", lines = saved.lines.take(1), steps = saved.steps.takeLast(1)))

        val replaced = checkNotNull(recipes.get(id))
        assertEquals("Better toast", replaced.title)
        assertEquals(1, replaced.lines.size)
        assertEquals(listOf("Butter it."), replaced.steps.map { it.body })
        assertEquals(saved.createdAtEpochMillis, replaced.createdAtEpochMillis)
        assertTrue(replaced.updatedAtEpochMillis >= saved.updatedAtEpochMillis)
        assertEquals(1, allRecipes().size)
        assertEquals(listOf(entry), shopping.observeEntries().first().map { it.id })
        assertEquals(1L, count("SELECT COUNT(*) FROM recipe_lines"))
        assertEquals(1L, count("SELECT COUNT(*) FROM recipe_steps"))

        assertFailsWith<IllegalArgumentException> { recipes.save(Recipe(title = " ")) }
        assertFailsWith<IllegalArgumentException> { recipes.save(saved.copy(id = 9_999)) }
    }

    @Test
    fun aRecipeCanBeRatedNotedAndArchived() = runBlocking<Unit> {
        val id = recipes.save(Recipe(title = "Soup"))
        val created = checkNotNull(recipes.get(id))

        recipes.setRating(id, 4)
        recipes.setNotes(id, "More pepper.")
        recipes.setArchived(id, true)

        val after = checkNotNull(recipes.get(id))
        assertEquals(4, after.rating)
        assertEquals("More pepper.", after.notes)
        assertTrue(after.archived)
        assertEquals("Soup", after.title)
        assertTrue(after.updatedAtEpochMillis >= created.updatedAtEpochMillis)
        assertTrue(recipes.observeAll().first().single().archived, "archived recipes are still listed, marked archived")

        recipes.setRating(id, 99)
        assertEquals(5, recipes.get(id)?.rating)
        recipes.setRating(id, -3)
        assertEquals(0, recipes.get(id)?.rating)
        recipes.setArchived(id, false)
        assertFalse(checkNotNull(recipes.get(id)).archived)
    }

    @Test
    fun deletingARecipeCascadesItsLinesStepsAndShoppingEntries() = runBlocking<Unit> {
        val result = recipes.importBundle(RecipeBundle.starter())
        val victim = result.recipeIds.first()
        val keeper = result.recipeIds[1]
        shopping.addRecipe(victim, 4)
        shopping.addRecipe(keeper, 2)
        val victimLines = checkNotNull(recipes.get(victim)).lines.size
        val victimSteps = checkNotNull(recipes.get(victim)).steps.size
        assertTrue(victimLines > 0 && victimSteps > 0)
        assertEquals(victimLines.toLong(), count("SELECT COUNT(*) FROM recipe_lines WHERE recipe_id = $victim"))
        assertEquals(victimSteps.toLong(), count("SELECT COUNT(*) FROM recipe_steps WHERE recipe_id = $victim"))
        assertEquals(1L, count("SELECT COUNT(*) FROM shopping_entries WHERE recipe_id = $victim"))
        val keeperLines = count("SELECT COUNT(*) FROM recipe_lines WHERE recipe_id = $keeper")

        recipes.delete(victim)

        assertNull(recipes.get(victim))
        assertEquals(3, allRecipes().size)
        assertEquals(0L, count("SELECT COUNT(*) FROM recipe_lines WHERE recipe_id = $victim"))
        assertEquals(0L, count("SELECT COUNT(*) FROM recipe_steps WHERE recipe_id = $victim"))
        assertEquals(0L, count("SELECT COUNT(*) FROM shopping_entries WHERE recipe_id = $victim"))
        assertEquals(keeperLines, count("SELECT COUNT(*) FROM recipe_lines WHERE recipe_id = $keeper"), "the other recipes are untouched")
        assertEquals(listOf(keeper), shopping.observeEntries().first().map { it.recipeId })
        assertEquals(40, allIngredients().size, "ingredients are not recipes' to delete")
    }

    @Test
    fun deletingAnIngredientOutsideTheRepositoryKeepsTheLineWithoutIt() = runBlocking<Unit> {
        // The foreign key says SET_NULL; the repository never does this while a line uses the ingredient.
        recipes.importBundle(RecipeBundle.starter())
        val onion = ingredientNamed("Brown onions")
        val linesBefore = allRecipes().sumOf { it.lines.size }
        assertTrue(allRecipes().flatMap { it.lines }.any { it.ingredientId == onion.id })

        container.database.foodDao().deleteIngredient(onion.id)

        assertEquals(linesBefore, allRecipes().sumOf { it.lines.size }, "no line was lost")
        assertTrue(allRecipes().flatMap { it.lines }.none { it.ingredientId == onion.id })
    }

    // ---- the shopping list ----------------------------------------------------------------------

    @Test
    fun shoppingEntriesMarksClearTickedAndClearList() = runBlocking<Unit> {
        val result = recipes.importBundle(RecipeBundle.starter())
        val stirFry = recipeTitled(stirFryTitle)
        val chilli = recipeTitled(chilliTitle)
        val chicken = ingredientNamed("Chicken breast fillets")
        val rice = ingredientNamed("Long-grain white rice")
        val salt = ingredientNamed("Salt")
        assertEquals(4, result.recipeIds.size)

        assertTrue(shopping.observeEntries().first().isEmpty())
        val stirFryEntry = shopping.addRecipe(stirFry.id, 4)
        val chilliEntry = shopping.addRecipe(chilli.id, 8)
        val extraEntry = shopping.addIngredient(salt.id, 750.0)
        val wholePackEntry = shopping.addIngredient(rice.id)
        val binBags = checkNotNull(shopping.addFreeText("  Bin bags "))
        val washingUp = checkNotNull(shopping.addFreeText("Washing-up liquid"))
        assertNull(shopping.addFreeText("   "), "blank text adds nothing")
        shopping.setFreeTextChecked(binBags, true)
        shopping.setServings(chilliEntry, 6)
        shopping.setChecked(chicken.id, true)
        shopping.setHaveIt(rice.id, true)

        val entries = shopping.observeEntries().first()
        assertEquals(listOf(stirFryEntry, chilliEntry, extraEntry, wholePackEntry, binBags, washingUp), entries.map { it.id }, "in the order added")
        assertEquals(stirFry.id, entries[0].recipeId)
        assertEquals(4, entries[0].servings)
        assertEquals(6, entries[1].servings)
        assertEquals(salt.id, entries[2].ingredientId)
        assertEquals(750.0, entries[2].quantity)
        assertNull(entries[3].quantity, "a whole pack")
        assertEquals("Bin bags", entries[4].freeText)
        assertTrue(entries[4].checked)
        assertFalse(entries[5].checked)
        assertTrue(entries.all { it.addedAtEpochMillis > 0 })
        val marks = shopping.observeMarks().first()
        assertEquals(setOf(chicken.id, rice.id), marks.map { it.ingredientId }.toSet())
        assertTrue(marks.single { it.ingredientId == chicken.id }.let { it.checked && !it.haveIt })
        assertTrue(marks.single { it.ingredientId == rice.id }.let { !it.checked && it.haveIt })

        // The list builds from what was stored.
        val all = allIngredients()
        val prices = Prices.resolveAll(all, emptyList(), today)
        val list = ShoppingList.build(entries, marks, allRecipes(), all, prices)
        val chickenLine = list.lines.single { it.ingredientId == chicken.id }
        val riceLine = list.lines.single { it.ingredientId == rice.id }
        assertTrue(chickenLine.checked && !chickenLine.haveIt)
        assertTrue(riceLine.haveIt && riceLine.packs == 0, "I have it: nothing to buy")
        assertEquals(listOf("Bin bags", "Washing-up liquid"), list.freeText.map { it.text })
        assertTrue(list.freeText.first().checked)
        assertTrue(list.lines.single { it.ingredientId == salt.id }.isExtra)

        // A second tick on the same line changes it, it does not add a row.
        shopping.setChecked(chicken.id, false)
        shopping.setChecked(chicken.id, true)
        assertEquals(2, shopping.observeMarks().first().size)

        // Clear ticked: the ticked free-text item goes and every mark is reset; entries for recipes and ingredients stay.
        shopping.clearTicked()
        val afterTicked = shopping.observeEntries().first()
        assertEquals(listOf(stirFryEntry, chilliEntry, extraEntry, wholePackEntry, washingUp), afterTicked.map { it.id })
        assertTrue(shopping.observeMarks().first().isEmpty())

        // Removing one entry, then clearing the whole list.
        shopping.removeEntry(extraEntry)
        assertEquals(4, shopping.observeEntries().first().size)
        shopping.setChecked(chicken.id, true)
        shopping.clearList()
        assertTrue(shopping.observeEntries().first().isEmpty())
        assertTrue(shopping.observeMarks().first().isEmpty())
        assertEquals(4, allRecipes().size, "the recipes themselves are untouched")
        assertEquals(40, allIngredients().size)
    }

    @Test
    fun addingARecipeTwiceAddsTwoEntriesThatTheListSums() = runBlocking<Unit> {
        recipes.importBundle(RecipeBundle.starter())
        val stirFry = recipeTitled(stirFryTitle)

        shopping.addRecipe(stirFry.id, 2)
        shopping.addRecipe(stirFry.id, 2)
        shopping.addRecipe(stirFry.id, 0)

        val entries = shopping.observeEntries().first()
        assertEquals(listOf(2, 2, 1), entries.map { it.servings }, "servings are at least 1")
        val all = allIngredients()
        val prices = Prices.resolveAll(all, emptyList(), today)
        val once = ShoppingList.build(entries.take(1), emptyList(), allRecipes(), all, prices)
        val summed = ShoppingList.build(entries.take(2), emptyList(), allRecipes(), all, prices)
        val chicken = ingredientNamed("Chicken breast fillets")
        assertEquals(
            2 * once.lines.single { it.ingredientId == chicken.id }.quantity,
            summed.lines.single { it.ingredientId == chicken.id }.quantity,
            0.001,
        )
    }
}
