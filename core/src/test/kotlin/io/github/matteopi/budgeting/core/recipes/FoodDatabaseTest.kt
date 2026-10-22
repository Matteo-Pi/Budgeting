package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The built-in food list (docs/PLAN.md §11.12). */
class FoodDatabaseTest {

    private val foods = FoodDatabase.all()
    private val sources = FoodDatabase.bundle.ingredients.associateBy { it.key }

    private fun matched(line: String): String? = FoodDatabase.match(IngredientLines.parse(line).name, line)?.key

    @Test
    fun `it decodes with about 170 foods and no recipes`() {
        assertTrue(foods.size in 160..200, "${foods.size} foods")
        assertTrue(FoodDatabase.bundle.recipes.isEmpty())
        assertEquals(foods.size, foods.map { it.key }.toSet().size, "keys are unique")
        assertEquals(foods.size, foods.map { it.name.lowercase() }.toSet().size, "names are unique")
    }

    @Test
    fun `every food has a pack, a dated Tesco price and all eight label values`() {
        for (food in foods) {
            val i = food.ingredient
            assertTrue(i.packSize > 0 && i.packLabel.isNotBlank(), "${food.key} pack")
            assertNotNull(i.price, "${food.key} price")
            assertTrue(i.price!!.pence > 0, "${food.key} price")
            assertNotNull(i.priceDate, "${food.key} date")
            assertTrue(i.priceDate!! in LocalDate.of(2026, 10, 15)..FoodDatabase.PRICES_FOUND, "${food.key} date")
            assertEquals("Tesco", i.store, food.key)
            assertTrue(!i.productName.isNullOrBlank(), "${food.key} product")
            assertFalse(i.per100.hasUnknown, "${food.key} nutrition")
            assertNotNull(i.per100.addedSugars, "${food.key} added sugars")
            assertEquals(0L, i.id, food.key)
            assertTrue(i.note.isNotBlank(), "${food.key} note")
        }
    }

    @Test
    fun `the foods this list adds are typical values for the food as weighed`() {
        val starter = RecipeBundle.starter().ingredients.map { it.key }.toSet()
        val added = foods.filter { it.key !in starter }
        assertTrue(added.size >= 120, "${added.size} added")
        for (food in added) {
            assertEquals(NutritionSource.ESTIMATE, food.ingredient.nutritionSource, food.key)
            assertEquals(FoodDatabase.PRICES_FOUND, food.ingredient.priceDate, food.key)
        }
    }

    @Test
    fun `no part is more than its whole`() {
        for (food in foods) {
            val n = food.ingredient.per100
            assertTrue(n.saturates!! <= n.fat!!, "${food.key} saturates")
            assertTrue(n.sugars!! <= n.carbs!!, "${food.key} sugars")
            assertTrue(n.addedSugars!! <= n.sugars!!, "${food.key} added sugars")
            assertTrue(n.salt!! <= 100.0, "${food.key} salt")
            val grams = n.fat!! + n.carbs!! + n.fibre!! + n.protein!! + n.salt!!
            assertTrue(grams <= 101.0, "${food.key}: $grams g in 100")
        }
    }

    /** Vinegar's energy is acetic acid, which labels count at 3.6 kcal/g and these factors don't. */
    private val acids = setOf("vinegar_white_wine")

    @Test
    fun `energy agrees with the fat, carbs, protein and fibre`() {
        for (food in foods) {
            if (food.key in acids) continue
            val n = food.ingredient.per100
            val worked = 4 * n.carbs!! + 4 * n.protein!! + 9 * n.fat!! + 2 * n.fibre!!
            assertTrue(abs(worked - n.kcal!!) <= 0.15 * n.kcal!! + 10, "${food.key}: ${n.kcal} kcal, worked out $worked")
        }
    }

    @Test
    fun `sugar, honey, jam and sweet sauces are the foods with added sugars`() {
        val sweet = foods.filter { it.ingredient.per100.addedSugars!! > 0 }.map { it.key }.toSet()
        for (key in listOf("sugar_granulated", "sugar_brown", "honey", "jam", "ketchup", "sweet_chilli", "hoisin", "baked_beans")) {
            assertTrue(key in sweet, key)
        }
        for (key in listOf("milk_semi", "apples", "bananas", "sultanas", "rice", "oat_drink", "chickpeas")) {
            assertFalse(key in sweet, key)
        }
    }

    @Test
    fun `the starter foods are the starter book's`() {
        for (starter in RecipeBundle.starter().ingredients) {
            val food = assertNotNull(sources[starter.key], starter.key)
            assertEquals(starter, food.copy(aliases = emptyList()), starter.key)
        }
    }

    @Test
    fun `files the app writes have no other names`() {
        assertFalse(RecipeBundle.encode(RecipeBundle.starter()).contains("aliases"))
        assertTrue(RecipeBundle.encode(FoodDatabase.bundle).contains("aliases"))
    }

    @Test
    fun `names and other names belong to one food`() {
        // Each name and other name, with its describing words ("chopped") but not its bracketed ones, is one food's.
        val owner = HashMap<String, String>()
        for (source in FoodDatabase.bundle.ingredients) {
            for (key in (listOf(source.name) + source.aliases).map { FoodDatabase.words(it).sorted().joinToString(" ") }.toSet()) {
                val before = owner.put(key, source.key)
                assertTrue(before == null, "\"$key\" is a name of both $before and ${source.key}")
            }
        }
        // "Chopped tomatoes (tin)" and "Tomatoes (round)" are both "tomato" to the matching; the line's own words decide.
        assertEquals("tomatoes", matched("4 tomatoes"))
        assertEquals("tomatoes", matched("2 ripe tomatoes"))
        assertEquals("tomatoes_chopped", matched("400 g chopped tomatoes"))
        assertEquals("tomatoes_chopped", matched("1 tin of tomatoes"))
    }

    @Test
    fun `pulses are pulses and counted lines have portions`() {
        for (key in listOf("chickpeas", "black_beans", "butter_beans", "cannellini", "green_lentils", "kidney_beans", "red_lentils")) {
            assertEquals(VegKind.PULSE, FoodDatabase.byKey(key)!!.ingredient.vegKind, key)
        }
        assertEquals(240.0, Quantities.portion(FoodDatabase.byKey("chickpeas")!!.ingredient, "tins")?.amount)
        assertEquals(50.0, Quantities.portion(FoodDatabase.byKey("eggs")!!.ingredient, "each")?.amount)
        assertEquals(10.0, Quantities.portion(FoodDatabase.byKey("butter")!!.ingredient, "knob")?.amount)
        assertEquals(VegKind.NONE, FoodDatabase.byKey("potatoes_white")!!.ingredient.vegKind, "potatoes don't count")
        assertEquals(FoodGroup.NONE, FoodDatabase.byKey("oat_drink")!!.ingredient.foodGroup, "oat drink isn't a grain")
        assertNull(FoodGroups.of(FoodDatabase.byKey("oat_drink")!!.ingredient))
        assertEquals(FoodGroup.NOODLES, FoodGroups.of(FoodDatabase.byKey("egg_noodles")!!.ingredient))
        assertEquals(FoodGroup.BEANS_LENTILS, FoodGroups.of(FoodDatabase.byKey("chickpeas")!!.ingredient))
        assertNull(FoodGroups.of(FoodDatabase.byKey("stock_cube_beef")!!.ingredient))
    }

    @Test
    fun `recipe lines find their food`() {
        assertEquals("chickpeas", matched("2 × 400 g tins chickpeas"))
        assertEquals("red_onions", matched("1 red onion, finely chopped"))
        assertEquals("penne", matched("200 g penne"))
        assertEquals("butter", matched("a knob of butter"))
        assertEquals("chickpeas", matched("1 tin garbanzo beans"))
        assertEquals("onion", matched("2 onions"))
        assertEquals("eggs", matched("3 large eggs"))
        assertEquals("tomatoes_chopped", matched("400 g tin chopped tomatoes"))
        assertEquals("veg_oil", matched("1 tbsp oil"))
        assertEquals("stock_cube_veg", matched("500 ml vegetable stock"))
        assertEquals("black_pepper", matched("black pepper"))
        assertEquals("yogurt_greek_0", matched("150 g Greek yoghurt"))
        assertEquals("creme_fraiche", matched("2 tbsp crème fraîche"))
        assertEquals("coriander_fresh", matched("a handful of fresh coriander"))
    }

    @Test
    fun `a food it doesn't have is no match`() {
        assertNull(matched("1 dragon fruit"))
        assertNull(matched("200 g venison"))
        assertNull(FoodDatabase.match(""))
    }

    @Test
    fun `search starts with the names that start with the text`() {
        assertEquals("chickpeas", FoodDatabase.search("chick").first().key)
        assertEquals("chickpeas", FoodDatabase.search("garbanzo").first().key)
        assertEquals("creme_fraiche", FoodDatabase.search("creme").first().key)
        assertTrue(FoodDatabase.search("rice").take(4).all { it.ingredient.name.contains("rice", ignoreCase = true) })
        assertTrue(FoodDatabase.search("zzz").isEmpty())
        assertEquals(foods.size, FoodDatabase.search("  ").size)
    }

    @Test
    fun `a food becomes the user's ingredient with its price, nutrition and portions`() {
        val chickpeas = FoodDatabase.byKey("chickpeas")!!.toIngredient(nowMillis = 1234L)
        assertEquals(0L, chickpeas.id)
        assertEquals(1234L, chickpeas.createdAtEpochMillis)
        assertEquals("Chickpeas (tin, drained)", chickpeas.name)
        assertEquals(Money(41), chickpeas.price)
        assertEquals(240.0, chickpeas.packSize)
        assertEquals(121.0, chickpeas.per100.kcal)
        assertEquals(listOf(Portion("tin", 240.0)), chickpeas.portions)
        assertEquals(Shelf.CUPBOARD, chickpeas.shelf)
    }
}
