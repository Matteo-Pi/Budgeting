package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeBundleTest {

    private val valid = """
        {
          "format": "budgeting-recipes",
          "version": 1,
          "about": "Test",
          "ingredients": [
            {"key": "egg", "name": "Eggs", "unit": "G", "pack": {"label": "6 eggs", "size": 360, "pricePence": 150, "priceDate": "2026-10-15", "store": "Tesco"},
             "per100": {"kcal": 143, "protein": 12.6}, "portions": [{"name": "each", "amount": 60}]},
            {"key": "milk", "name": "Milk", "unit": "ML", "shelf": "DAIRY", "veg": "NONE", "pack": {"size": 1000, "pricePence": 120}}
          ],
          "recipes": [
            {"key": "omelette", "title": "Omelette", "serves": 1, "source": "MINE",
             "lines": [{"text": "2 eggs", "ingredient": "egg", "quantity": 120}, {"text": "pinch of salt"}],
             "steps": [{"title": "Whisk", "time": "1 min", "body": "Whisk."}], "tips": ["Low heat."]}
          ]
        }
    """.trimIndent()

    private fun failure(text: String): List<String> {
        val result = RecipeBundle.decode(text)
        assertIs<BundleDecodeResult.Failure>(result, "expected a failure, got $result")
        return result.problems
    }

    private fun success(text: String): RecipeBundle {
        val result = RecipeBundle.decode(text)
        assertIs<BundleDecodeResult.Success>(result, "expected a bundle, got $result")
        return result.bundle
    }

    // ---- decode -----------------------------------------------------------------------------

    @Test
    fun `a valid bundle decodes with defaults for what is left out`() {
        val bundle = success(valid)
        assertEquals(2, bundle.ingredients.size)
        assertEquals(1, bundle.recipes.size)
        val milk = bundle.ingredients[1]
        assertEquals("DAIRY", milk.shelf)
        assertEquals("TYPED", milk.nutritionSource)
        assertEquals("", milk.note)
        assertNull(milk.pack.priceDate)
        assertEquals(0, bundle.recipes.single().rating)
        assertTrue(bundle.recipes.single().storage.isEmpty())
        assertNull(bundle.recipes.single().lines[1].ingredient)
    }

    @Test
    fun `unknown fields are ignored`() {
        val withExtras = valid.replace("\"about\": \"Test\",", "\"about\": \"Test\", \"colour\": \"red\", \"nested\": {\"a\": [1, 2]},")
            .replace("\"key\": \"omelette\",", "\"key\": \"omelette\", \"calories\": 300,")
        assertEquals(1, success(withExtras).recipes.size)
    }

    @Test
    fun `the wrong format is refused`() {
        val problems = failure(valid.replace("budgeting-recipes", "budgeting-backup"))
        assertEquals(listOf("This doesn't look like a Budgeting recipe book."), problems)
        assertEquals(problems, failure("""{"version": 1, "ingredients": [], "recipes": []}"""))
        assertEquals(problems, failure("{}"))
    }

    @Test
    fun `a newer version is refused`() {
        val problems = failure(valid.replace("\"version\": 1", "\"version\": 2"))
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("newer version"), problems.toString())
    }

    @Test
    fun `an unrecognised version is refused`() {
        assertTrue(failure(valid.replace("\"version\": 1", "\"version\": 0")).single().contains("unrecognised"))
        assertTrue(failure(valid.replace("\"version\": 1,", "")).single().contains("unrecognised"))
    }

    @Test
    fun `text that is not a bundle is refused`() {
        assertTrue(failure("").isNotEmpty())
        assertTrue(failure("not json").isNotEmpty())
        assertTrue(failure("[1, 2, 3]").isNotEmpty())
        assertTrue(failure("{\"format\": ").isNotEmpty())
    }

    @Test
    fun `a line pointing at a missing key is a problem that names the line`() {
        val problems = failure(valid.replace("\"ingredient\": \"egg\"", "\"ingredient\": \"duck_egg\""))
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("omelette"), problems.toString())
        assertTrue(problems.single().contains("duck_egg"), problems.toString())
        assertTrue(problems.single().contains("line 1"), problems.toString())
        assertTrue(problems.single().contains("2 eggs"), problems.toString())
    }

    @Test
    fun `a bad unit is a problem`() {
        val problems = failure(valid.replace("\"unit\": \"G\"", "\"unit\": \"KG\""))
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("egg"), problems.toString())
        assertTrue(problems.single().contains("\"KG\"") && problems.single().contains("G or ML"), problems.toString())
        assertEquals(BaseUnit.G, success(valid.replace("\"unit\": \"G\"", "\"unit\": \"g\"")).toModels().ingredients[0].unit)
    }

    @Test
    fun `other bad values are problems`() {
        assertTrue(failure(valid.replace("\"DAIRY\"", "\"FRIDGE\"")).single().contains("shelf"))
        assertTrue(failure(valid.replace("\"veg\": \"NONE\"", "\"veg\": \"FRUIT\"")).single().contains("veg"))
        assertTrue(failure(valid.replace("\"source\": \"MINE\"", "\"source\": \"WEB\"")).single().contains("source"))
        assertTrue(failure(valid.replace("\"serves\": 1", "\"serves\": 0")).single().contains("serve"))
        assertTrue(failure(valid.replace("\"size\": 360", "\"size\": 0")).single().contains("pack size"))
        assertTrue(failure(valid.replace("2026-10-15", "15/10/2026")).single().contains("price date"))
        assertTrue(failure(valid.replace("\"serves\": 1", "\"serves\": 1, \"rating\": 7")).single().contains("rating"))
        assertTrue(failure(valid.replace("\"quantity\": 120", "\"quantity\": -1")).single().contains("negative"))
    }

    @Test
    fun `duplicate keys are problems`() {
        val twice = valid.replace(
            "{\"key\": \"milk\"",
            "{\"key\": \"egg\", \"name\": \"Other eggs\", \"unit\": \"G\", \"pack\": {\"size\": 1}}, {\"key\": \"milk\"",
        )
        val problems = failure(twice)
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("more than once"))
    }

    @Test
    fun `all the problems are reported together`() {
        val broken = valid.replace("\"unit\": \"G\"", "\"unit\": \"KG\"")
            .replace("\"ingredient\": \"egg\"", "\"ingredient\": \"nope\"")
            .replace("\"serves\": 1", "\"serves\": 0")
        assertEquals(3, failure(broken).size)
    }

    @Test
    fun `a missing required field is reported`() {
        val problems = failure(valid.replace("\"name\": \"Eggs\", ", ""))
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("missing"), problems.toString())
    }

    @Test
    fun `an ingredient or recipe without its key is reported as missing`() {
        val noIngredientKey = failure(valid.replace("{\"key\": \"egg\", ", "{"))
        assertEquals(1, noIngredientKey.size)
        assertTrue(noIngredientKey.single().contains("missing") && noIngredientKey.single().contains("key"), noIngredientKey.toString())
        val noRecipeKey = failure(valid.replace("{\"key\": \"omelette\", ", "{"))
        assertTrue(noRecipeKey.single().contains("key"), noRecipeKey.toString())
        assertTrue(failure(valid.replace("\"key\": \"egg\",", "\"key\": \"\",")).any { it.contains("no key") })
    }

    // ---- models -----------------------------------------------------------------------------

    @Test
    fun `models get provisional ids and lines point at them`() {
        val models = success(valid).toModels()
        assertEquals(listOf(1L, 2L), models.ingredients.map { it.id })
        assertEquals(listOf(1L), models.recipes.map { it.id })
        assertEquals(mapOf("egg" to 1L, "milk" to 2L), models.ingredientKeys)
        assertEquals(mapOf("omelette" to 1L), models.recipeKeys)
        val lines = models.recipes.single().lines
        assertEquals(1L, lines[0].ingredientId)
        assertEquals(120.0, lines[0].quantity)
        assertNull(lines[1].ingredientId)
        assertNull(lines[1].quantity)
        val egg = models.ingredients[0]
        assertEquals("Eggs", egg.name)
        assertEquals(BaseUnit.G, egg.unit)
        assertEquals(360.0, egg.packSize)
        assertEquals("6 eggs", egg.packLabel)
        assertEquals(Money(150), egg.price)
        assertEquals(LocalDate.of(2026, 10, 15), egg.priceDate)
        assertEquals("Tesco", egg.store)
        assertEquals(Nutrition(kcal = 143.0, protein = 12.6), egg.per100)
        assertEquals(listOf(Portion("each", 60.0)), egg.portions)
        val milk = models.ingredients[1]
        assertEquals(BaseUnit.ML, milk.unit)
        assertEquals(Shelf.DAIRY, milk.shelf)
        assertEquals(Money(120), milk.price)
        assertNull(milk.priceDate)
        assertNull(milk.store)
    }

    @Test
    fun `steps, tips, equipment and storage become steps of their kinds in order`() {
        val chilli = StarterBook.recipe("chilli")
        assertEquals(9, chilli.method.size)
        assertEquals(4, chilli.tips.size)
        assertEquals(3, chilli.equipment.size)
        assertEquals(7, chilli.storage.size)
        assertEquals(
            listOf(StepKind.STEP, StepKind.TIP, StepKind.EQUIPMENT, StepKind.STORAGE),
            chilli.steps.map { it.kind }.distinct(),
        )
        assertEquals("Prep everything", chilli.steps.first().title)
        assertEquals("~20 min", chilli.steps.first().time)
        assertNull(chilli.tips.first().title)
    }

    @Test
    fun `a recipe's added date becomes its creation time`() {
        val chilli = StarterBook.recipe("chilli")
        val expected = LocalDate.of(2026, 10, 17).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, chilli.createdAtEpochMillis)
        assertEquals(RecipeSource.CLAUDE, chilli.source)
        assertEquals(RecipeSource.IMPORTED, success(valid.replace("\"source\": \"MINE\",", "")).toModels().recipes.single().source)
    }

    // ---- the starter book -------------------------------------------------------------------

    @Test
    fun `the starter book is the file in the resources`() {
        val text = RecipeBundle::class.java.getResourceAsStream(RecipeBundle.STARTER_RESOURCE)!!.bufferedReader().readText()
        assertEquals(success(text), RecipeBundle.starter())
        assertEquals("budgeting-recipes", RecipeBundle.starter().format)
        assertEquals(1, RecipeBundle.starter().version)
    }

    @Test
    fun `the starter book has four recipes and forty ingredients that all hang together`() {
        val bundle = RecipeBundle.starter()
        assertEquals(listOf("stir-fry", "traybake", "braise", "chilli"), bundle.recipes.map { it.key })
        assertEquals(40, bundle.ingredients.size)
        assertEquals(40, bundle.ingredients.map { it.key }.toSet().size)
        val models = bundle.toModels()
        assertEquals((1L..40L).toList(), models.ingredients.map { it.id })
        assertEquals((1L..4L).toList(), models.recipes.map { it.id })
        for ((recipe, source) in models.recipes.zip(bundle.recipes)) {
            assertEquals(source.lines.size, recipe.lines.size)
            recipe.lines.zip(source.lines).forEach { (line, original) ->
                val key = original.ingredient
                if (key == null) {
                    assertNull(line.ingredientId, original.text)
                } else {
                    assertEquals(models.ingredientKeys.getValue(key), line.ingredientId, original.text)
                }
            }
        }
    }

    @Test
    fun `the starter ingredients keep their data`() {
        val chicken = StarterBook.ingredient("chicken_breast")
        assertEquals("Chicken breast fillets", chicken.name)
        assertEquals(Shelf.MEAT, chicken.shelf)
        assertEquals(BaseUnit.G, chicken.unit)
        assertEquals(320.0, chicken.packSize)
        assertEquals(Money(244), chicken.price)
        assertEquals(LocalDate.of(2026, 10, 15), chicken.priceDate)
        assertEquals("Tesco", chicken.store)
        assertEquals(listOf(Portion("fillet", 160.0)), chicken.portions)
        assertEquals(NutritionSource.LABEL, chicken.nutritionSource)
        assertEquals(115.0, chicken.per100.kcal)
        assertEquals("Tesco Smoked Streaky Bacon Rashers 14 Pack 300g", StarterBook.ingredient("bacon_streaky").productName)
        assertTrue(StarterBook.ingredient("bacon_streaky").sourceUrl!!.startsWith("https://www.tesco.com/"))
        assertEquals(VegKind.PULSE, StarterBook.ingredient("kidney_beans").vegKind)
        assertEquals(VegKind.PULSE, StarterBook.ingredient("red_lentils").vegKind)
        assertEquals(VegKind.NONE, StarterBook.ingredient("potato").vegKind)
        assertEquals(BaseUnit.ML, StarterBook.ingredient("olive_oil").unit)
        assertEquals(NutritionSource.ESTIMATE, StarterBook.ingredient("beef_mince_5").nutritionSource)
        assertTrue(StarterBook.ingredients.all { !it.per100.hasUnknown }, "every starter value is written out")
    }

    @Test
    fun `the starter recipes keep their headers`() {
        val stirFry = StarterBook.recipe("stir-fry")
        assertEquals("Chicken & Vegetable Stir-Fry", stirFry.title)
        assertEquals("Stir-fry", stirFry.shortTitle)
        assertEquals(2, stirFry.serves)
        assertEquals(30, stirFry.prepMins)
        assertEquals(15, stirFry.cookMins)
        assertEquals(45, stirFry.totalMins)
        assertEquals(listOf("chicken", "rice", "quick"), stirFry.tags)
        assertEquals(20, stirFry.lines.size)
        assertTrue(stirFry.lines.single { it.text.contains("oyster") }.optional)
        assertEquals(4, StarterBook.recipe("chilli").serves)
        assertEquals(RecipeSource.CLAUDE, StarterBook.recipe("braise").source)
    }

    // ---- encode -----------------------------------------------------------------------------

    @Test
    fun `an encoded bundle decodes to the same bundle`() {
        val starter = RecipeBundle.starter()
        assertEquals(starter, success(RecipeBundle.encode(starter)))
        val small = success(valid)
        assertEquals(small, success(RecipeBundle.encode(small)))
        assertTrue(RecipeBundle.encode(small).contains("budgeting-recipes"))
    }
}
