package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.core.recipes.StarterBook
import io.github.matteopi.budgeting.core.recipes.VegKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vitamins and minerals, intakes and recipe portions (docs/PLAN.md §14.2, §14.4). */
class MicrosAndIntakesTest {

    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-6, message: String? = null) =
        assertTrue(Math.abs(expected - actual) <= tolerance, "${message ?: ""} expected $expected, was $actual")

    @Test
    fun `the references are the label NRVs, vitamin D the NHS's 10 micrograms`() {
        assertEquals(12, Micro.entries.size)
        assertEquals(800.0, Micro.VITAMIN_A.reference)
        assertEquals(80.0, Micro.VITAMIN_C.reference)
        assertEquals(10.0, Micro.VITAMIN_D.reference)
        assertEquals(2.5, Micro.VITAMIN_B12.reference)
        assertEquals(14.0, Micro.IRON.reference)
        assertEquals(2000.0, Micro.POTASSIUM.reference)
        assertEquals(50.0, Micro.IRON.percent(7.0))
        assertEquals("µg", Micro.FOLATE.unit)
        assertEquals("1.2 mg", Micro.IRON.format(1.234))
        assertEquals("35 µg", Micro.FOLATE.format(35.4))
        assertEquals(21, IntakeKey.entries.size)
        assertEquals(IntakeKey.ZINC, IntakeKey.of(Micro.ZINC))
        assertEquals(IntakeKey.SALT, IntakeKey.of(Nutrient.SALT))
    }

    @Test
    fun `every food of the list and the starter book has all twelve, within sane ranges`() {
        val upper = mapOf(
            Micro.VITAMIN_A to 10_000.0, Micro.VITAMIN_C to 400.0, Micro.VITAMIN_D to 30.0, Micro.VITAMIN_E to 60.0,
            Micro.VITAMIN_B6 to 5.0, Micro.VITAMIN_B12 to 100.0, Micro.FOLATE to 3000.0, Micro.CALCIUM to 7000.0,
            Micro.IRON to 130.0, Micro.POTASSIUM to 4000.0, Micro.MAGNESIUM to 800.0, Micro.ZINC to 80.0,
        )
        val all = FoodDatabase.all().map { it.key to it.ingredient } + StarterBook.ingredients.map { it.name to it }
        for ((key, ingredient) in all) {
            for (micro in Micro.entries) {
                val value = ingredient.micros[micro]
                assertTrue(value != null, "$key has no ${micro.label}")
                assertTrue(value!! >= 0.0 && value <= upper.getValue(micro), "$key ${micro.label} = $value")
            }
        }
    }

    @Test
    fun `typical values sit where they should`() {
        fun micro(key: String, micro: Micro) = FoodDatabase.byKey(key)!!.ingredient.micros[micro]!!
        assertTrue(micro("broccoli", Micro.VITAMIN_C) > 50)
        assertTrue(micro("spinach_baby", Micro.FOLATE) > 100)
        assertTrue(micro("bread_white", Micro.CALCIUM) > 100, "UK flour is fortified with calcium")
        assertTrue(micro("bananas", Micro.POTASSIUM) > 300)
        assertEquals(0.0, micro("rice", Micro.VITAMIN_B12))
        assertEquals(0.0, micro("salt", Micro.VITAMIN_C))
    }

    @Test
    fun `the starter book's foods are the food list's, micros included`() {
        val foods = FoodDatabase.all().associateBy { it.key }
        for (source in RecipeBundle.starter().ingredients) {
            assertEquals(foods.getValue(source.key).ingredient.micros, source.per100.micros, source.key)
        }
    }

    private val bread = Ingredient(
        id = 7,
        name = "White bread",
        per100 = Nutrition(kcal = 235.0, fat = 1.9, saturates = 0.4, carbs = 45.0, sugars = 3.5, fibre = 2.8, protein = 8.4, salt = 0.98, addedSugars = 2.0),
        micros = Micros(calcium = 177.0, iron = 1.6),
        portions = listOf(Portion("slice", 40.0)),
    )

    @Test
    fun `three slices of toast is 120 g of bread, with unknown micros named`() {
        val intake = Intakes.ofFood(bread, 120.0)
        near(282.0, intake.kcal)
        near(10.08, intake.protein)
        near(212.4, intake[Micro.CALCIUM])
        near(1.92, intake[Micro.IRON])
        assertTrue(IntakeKey.VITAMIN_C in intake.unknown)
        assertTrue(IntakeKey.KCAL !in intake.unknown)
        assertEquals(0.0, intake.vegGrams)
    }

    @Test
    fun `veg and pulses count their grams and added sugars follow the fresh-produce rule`() {
        val carrots = Ingredient(id = 1, name = "Carrots", vegKind = VegKind.VEG, shelf = io.github.matteopi.budgeting.core.recipes.Shelf.FRUIT_VEG, per100 = Nutrition(kcal = 42.0, sugars = 7.0))
        val intake = Intakes.ofFood(carrots, 80.0)
        assertEquals(80.0, intake.vegGrams)
        assertEquals(0.0, intake[Nutrient.ADDED_SUGARS])
        assertTrue(IntakeKey.ADDED_SUGARS !in intake.unknown)
        val lentils = carrots.copy(vegKind = VegKind.PULSE, shelf = io.github.matteopi.budgeting.core.recipes.Shelf.CUPBOARD)
        val pulse = Intakes.ofFood(lentils, 100.0)
        assertEquals(100.0, pulse.pulseGrams)
        assertTrue(IntakeKey.ADDED_SUGARS in pulse.unknown)
    }

    @Test
    fun `a portion of the chilli split four or five ways`() {
        val chilli = StarterBook.recipe("chilli")
        val four = Intakes.portionOf(chilli, StarterBook.ingredientById, StarterBook.prices)
        val numbers = StarterBook.numbers("chilli")
        near(numbers.nutrition.perPortion.kcal, four.intake.kcal, 1e-6, "same as the recipe page")
        near(numbers.nutrition.perPortion.protein, four.intake.protein, 1e-6)
        near(numbers.cost.totalPence / 4, four.costPence!!, 1e-6)
        assertEquals(0, four.unpriced)
        val five = Intakes.portionOf(chilli, StarterBook.ingredientById, StarterBook.prices, servings = 4.0, portions = 5.0)
        near(four.intake.kcal * 4 / 5, five.intake.kcal, 1e-6)
        val doubled = Intakes.portionOf(chilli, StarterBook.ingredientById, StarterBook.prices, servings = 8.0, portions = 8.0)
        near(four.intake.kcal, doubled.intake.kcal, 1e-6, "cooked for 8, split 8 ways")
        assertTrue(four.intake[Micro.IRON] > 2.0, "beef, beans and lentils")
        assertTrue(four.intake.unknown.isEmpty(), "${four.intake.unknown}")
    }

    @Test
    fun `the recipe numbers carry the vitamins per portion`() {
        val numbers = StarterBook.numbers("stir-fry")
        assertTrue(numbers.intake[Micro.VITAMIN_C] > 40, "broccoli and peppers")
        near(numbers.nutrition.perPortion.kcal, numbers.intake.kcal, 1e-6)
    }

    @Test
    fun `a quick entry knows only what was typed`() {
        val intake = Intakes.quick(kcal = 350.0, protein = 12.0)
        assertEquals(350.0, intake.kcal)
        assertEquals(12.0, intake.protein)
        assertTrue(IntakeKey.CARBS in intake.unknown)
        assertTrue(IntakeKey.KCAL !in intake.unknown && IntakeKey.PROTEIN !in intake.unknown)
    }

    @Test
    fun `intakes add, scale and keep their unknowns, and the mask round trips`() {
        val a = Intakes.ofFood(bread, 40.0)
        val b = Intakes.quick(100.0)
        val sum = a + b
        near(a.kcal + 100.0, sum.kcal)
        assertEquals(a.unknown + b.unknown, sum.unknown)
        near(a.kcal * 2, (a * 2.0).kcal)
        assertEquals(sum.unknown, Intake.unknownOf(sum.unknownMask))
        assertEquals(Intake.NONE, Intake.sum(emptyList()))
    }

    @Test
    fun `the ingredient's food in the list has no price for an amount with none`() {
        val eaten = Intakes.eatFood(bread, 80.0, null)
        assertNull(eaten.costPence)
        assertEquals(1, eaten.unpriced)
    }
}
