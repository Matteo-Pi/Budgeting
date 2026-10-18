package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeMathsTest {

    private val today = LocalDate.of(2026, 10, 17)

    private fun ing(
        id: Long,
        name: String = "Ingredient $id",
        unit: BaseUnit = BaseUnit.G,
        pack: Double = 100.0,
        price: Long? = 100,
        veg: VegKind = VegKind.NONE,
        per100: Nutrition = Nutrition(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        priceDate: LocalDate? = today,
    ) = Ingredient(
        id = id, name = name, unit = unit, packSize = pack, price = price?.let { Money(it) },
        priceDate = priceDate, per100 = per100, vegKind = veg,
    )

    private fun line(id: Long?, quantity: Double?, optional: Boolean = false) =
        RecipeLine(text = "line", ingredientId = id, quantity = quantity, optional = optional)

    private fun recipe(serves: Int, vararg lines: RecipeLine) = Recipe(title = "T", serves = serves, lines = lines.toList())

    private fun analyse(
        recipe: Recipe,
        ingredients: List<Ingredient>,
        servings: Int = recipe.serves,
        countOptional: Boolean = true,
        priced: List<Ingredient> = ingredients,
    ): RecipeNumbers = RecipeMaths.analyse(
        recipe, ingredients.associateBy { it.id }, Prices.resolveAll(priced, emptyList(), today), servings, countOptional,
    )

    // ---- cost -------------------------------------------------------------------------------

    @Test
    fun `the braise is 7·45 whole but 3·72 a portion`() {
        val n = StarterBook.numbers("braise")
        assertEquals(744.996, n.cost.totalPence, 0.001)
        assertEquals(Money(745), n.cost.whole)
        assertEquals(Money(372), n.cost.perPortion)
        // Halving the rounded £7.45 would give £3.73.
        assertEquals(373L, Quantities.roundHalfUp(n.cost.whole.pence / 2.0))
    }

    @Test
    fun `cost is summed unrounded and rounded half up once`() {
        // Three lines of 0.4 p each: 1.2 p. Rounding each line first would give 0 or 3.
        val a = ing(1, pack = 1000.0, price = 4)
        val n = analyse(recipe(1, line(1, 100.0), line(1, 100.0), line(1, 100.0)), listOf(a))
        assertEquals(1.2, n.cost.totalPence, 1e-9)
        assertEquals(Money(1), n.cost.whole)
        assertEquals(Money(1), n.cost.perPortion)
        // 2.5 p rounds up to 3 p.
        val eighth = ing(2, pack = 8.0, price = 1)
        assertEquals(Money(3), analyse(recipe(1, line(2, 20.0)), listOf(eighth)).cost.whole)
    }

    @Test
    fun `a line costs quantity times the price per base unit`() {
        val flour = ing(1, pack = 1500.0, price = 150)
        val n = analyse(recipe(4, line(1, 300.0)), listOf(flour))
        assertEquals(30.0, n.cost.totalPence, 1e-9)
        assertEquals(30.0, n.lines.single().costPence!!, 1e-9)
        assertEquals(300.0, n.lines.single().quantity)
        assertEquals(Money(30), n.cost.whole)
        assertEquals(Money(8), n.cost.perPortion, "7.5 p rounds half up")
    }

    @Test
    fun `from an empty cupboard is whole packs of each distinct ingredient`() {
        val rice = ing(1, pack = 400.0, price = 120)
        // 150 + 150 = 300 g is one pack; rounding each line up would make two.
        val n = analyse(recipe(2, line(1, 150.0), line(1, 150.0)), listOf(rice))
        assertEquals(Money(120), n.cost.fromEmptyCupboard)
        // Exactly two packs is two packs, not three.
        assertEquals(Money(240), analyse(recipe(2, line(1, 800.0)), listOf(rice)).cost.fromEmptyCupboard)
        assertEquals(Money(240), analyse(recipe(2, line(1, 800.0 + 1e-10)), listOf(rice)).cost.fromEmptyCupboard)
        assertEquals(Money(360), analyse(recipe(2, line(1, 800.001)), listOf(rice)).cost.fromEmptyCupboard)
    }

    @Test
    fun `from an empty cupboard adds the packs of different ingredients in whole pence`() {
        val a = ing(1, pack = 500.0, price = 199)
        val b = ing(2, pack = 1000.0, price = 52)
        val n = analyse(recipe(2, line(1, 600.0), line(2, 10.0)), listOf(a, b))
        assertEquals(Money(2 * 199 + 52), n.cost.fromEmptyCupboard)
    }

    @Test
    fun `ingredients without a price are left out and the cost is at least`() {
        val priced = ing(1, price = 100)
        val unpriced = ing(2, price = null)
        val n = analyse(recipe(2, line(1, 100.0), line(2, 100.0), line(2, 50.0)), listOf(priced, unpriced))
        assertEquals(Money(100), n.cost.whole)
        assertEquals(Money(50), n.cost.perPortion)
        assertEquals(listOf(2L), n.cost.missingPrice)
        assertTrue(n.cost.atLeast)
        assertNull(n.lines[1].costPence)
        assertFalse(analyse(recipe(2, line(1, 100.0)), listOf(priced)).cost.atLeast)
    }

    @Test
    fun `old prices are listed once`() {
        val old = ing(1, priceDate = today.minusDays(121))
        val fresh = ing(2, priceDate = today.minusDays(120))
        val n = analyse(recipe(1, line(1, 10.0), line(1, 10.0), line(2, 10.0)), listOf(old, fresh))
        assertEquals(listOf(1L), n.cost.oldPrice)
    }

    // ---- optional lines and scaling ---------------------------------------------------------

    @Test
    fun `optional lines count unless the switch is off`() {
        val a = ing(1, pack = 100.0, price = 100, per100 = Nutrition(kcal = 100.0, fat = 0.0, saturates = 0.0, carbs = 0.0, sugars = 0.0, fibre = 0.0, protein = 0.0, salt = 0.0))
        val b = ing(2, pack = 100.0, price = 50, per100 = Nutrition(kcal = 200.0, fat = 0.0, saturates = 0.0, carbs = 0.0, sugars = 0.0, fibre = 0.0, protein = 0.0, salt = 0.0))
        val r = recipe(1, line(1, 100.0), line(2, 100.0, optional = true))
        val on = analyse(r, listOf(a, b), countOptional = true)
        val off = analyse(r, listOf(a, b), countOptional = false)
        assertEquals(Money(150), on.cost.whole)
        assertEquals(Money(100), off.cost.whole)
        assertEquals(300.0, on.nutrition.perPortion.kcal, 1e-9)
        assertEquals(100.0, off.nutrition.perPortion.kcal, 1e-9)
        assertEquals(Money(150), on.cost.fromEmptyCupboard)
        assertEquals(Money(100), off.cost.fromEmptyCupboard)
        assertEquals(2, on.lines.size)
        assertEquals(1, off.lines.size)
        assertTrue(on.countOptional)
        assertFalse(off.countOptional)
        assertEquals(200.0, on.rawGramsPerPortion)
        assertEquals(100.0, off.rawGramsPerPortion)
    }

    @Test
    fun `cooking for more multiplies every quantity, but a portion stays the same`() {
        val chilli = StarterBook.recipe("chilli")
        val same = StarterBook.numbers("chilli")
        val eight = StarterBook.numbers("chilli", servings = 8)
        assertEquals(2.0, eight.factor)
        assertEquals(8, eight.servings)
        assertEquals(same.cost.perPortion, eight.cost.perPortion)
        assertEquals(same.cost.totalPence * 2, eight.cost.totalPence, 1e-6)
        assertEquals(Money(Quantities.roundHalfUp(same.cost.totalPence * 2)), eight.cost.whole)
        assertEquals(same.nutrition.perPortion.kcal, eight.nutrition.perPortion.kcal, 1e-9)
        assertEquals(same.veg, eight.veg, 1e-9)
        assertEquals(same.rawGramsPerPortion, eight.rawGramsPerPortion, 1e-9)
        assertEquals(500.0 * 2, eight.lines.first().quantity)
        assertEquals(chilli.lines.first().quantity!! * 2, eight.lines.first().quantity)
    }

    @Test
    fun `cooking for fewer divides every quantity`() {
        val n = StarterBook.numbers("chilli", servings = 2)
        assertEquals(0.5, n.factor)
        assertEquals(250.0, n.lines.first().quantity)
        assertEquals(StarterBook.numbers("chilli").nutrition.perPortion.protein, n.nutrition.perPortion.protein, 1e-9)
        assertEquals(StarterBook.numbers("chilli").cost.totalPence / 2, n.cost.totalPence, 1e-6)
    }

    @Test
    fun `the cupboard cost follows the number of packs at the new servings`() {
        val rice = ing(1, pack = 1000.0, price = 100)
        val r = recipe(2, line(1, 300.0))
        assertEquals(Money(100), analyse(r, listOf(rice), servings = 2).cost.fromEmptyCupboard)
        assertEquals(Money(100), analyse(r, listOf(rice), servings = 6).cost.fromEmptyCupboard)
        assertEquals(Money(200), analyse(r, listOf(rice), servings = 8).cost.fromEmptyCupboard)
        assertEquals(Money(120), analyse(r, listOf(rice), servings = 8).cost.whole)
    }

    @Test
    fun `scale factor is servings over the recipe's servings`() {
        assertEquals(1.5, RecipeMaths.scaleFactor(recipe(4), 6))
        assertEquals(0.5, RecipeMaths.scaleFactor(recipe(4), 2))
        assertEquals(1.0, RecipeMaths.scaleFactor(Recipe(title = "T", serves = 0), 1), "a recipe of 0 is treated as 1")
    }

    @Test
    fun `lines that cannot be counted are left out`() {
        val a = ing(1)
        val n = analyse(recipe(1, line(1, 100.0), line(null, 50.0), line(1, null), line(99, 50.0)), listOf(a))
        assertEquals(1, n.lines.size)
        assertEquals(0, n.lines.single().lineIndex)
        assertEquals(Money(100), n.cost.whole)
        assertTrue(n.cost.missingPrice.isEmpty())
    }

    // ---- nutrition --------------------------------------------------------------------------

    private val oats = Nutrition(kcal = 380.0, fat = 8.0, saturates = 1.5, carbs = 60.0, sugars = 1.0, fibre = 9.0, protein = 11.0, salt = 0.02)

    @Test
    fun `nutrition per portion is quantity over 100 times the value per 100 over the servings`() {
        val oat = ing(1, per100 = oats)
        val n = analyse(recipe(2, line(1, 80.0)), listOf(oat)).nutrition
        assertEquals(80 / 100.0 * 380.0 / 2, n.perPortion.kcal, 1e-9)
        assertEquals(80 / 100.0 * 8.0 / 2, n.perPortion.fat, 1e-9)
        assertEquals(80 / 100.0 * 1.5 / 2, n.perPortion.saturates, 1e-9)
        assertEquals(80 / 100.0 * 60.0 / 2, n.perPortion.carbs, 1e-9)
        assertEquals(80 / 100.0 * 1.0 / 2, n.perPortion.sugars, 1e-9)
        assertEquals(80 / 100.0 * 9.0 / 2, n.perPortion.fibre, 1e-9)
        assertEquals(80 / 100.0 * 11.0 / 2, n.perPortion.protein, 1e-9)
        assertEquals(80 / 100.0 * 0.02 / 2, n.perPortion.salt, 1e-9)
        for (nutrient in Nutrient.entries) assertEquals(n.perPortion[nutrient], n.perPortion.let { it[nutrient] })
    }

    @Test
    fun `energy in kilojoules and percentages of the reference intake`() {
        val oat = ing(1, per100 = oats)
        val n = analyse(recipe(1, line(1, 500.0)), listOf(oat)).nutrition
        assertEquals(1900.0, n.perPortion.kcal, 1e-9)
        assertEquals(1900 * 4.184, n.kj, 1e-9)
        assertEquals(1900 * 4.184, n.perPortion.kj, 1e-9)
        assertEquals(95.0, n.percentRi(Nutrient.KCAL), 1e-9)
        assertEquals(40.0 / 70 * 100, n.percentRi(Nutrient.FAT), 1e-9)
        assertEquals(7.5 / 20 * 100, n.percentRi(Nutrient.SATURATES), 1e-9)
        assertEquals(300.0 / 260 * 100, n.percentRi(Nutrient.CARBS), 1e-9)
        assertEquals(5.0 / 90 * 100, n.percentRi(Nutrient.SUGARS), 1e-9)
        assertEquals(55.0 / 50 * 100, n.percentRi(Nutrient.PROTEIN), 1e-9)
        assertEquals(0.1 / 6 * 100, n.percentRi(Nutrient.SALT), 1e-9)
        assertEquals(45.0 / 30 * 100, n.percentRi(Nutrient.FIBRE), 1e-9, "fibre is against the 30 g guideline")
        assertEquals(8400.0, ReferenceIntakes.KJ)
        assertEquals(30.0, ReferenceIntakes.FIBRE_GUIDELINE)
    }

    @Test
    fun `per 100 g uses the raw weight of the counted lines with ml as g`() {
        val oat = ing(1, per100 = oats)
        val stock = ing(2, unit = BaseUnit.ML, per100 = Nutrition(kcal = 10.0, fat = 0.0, saturates = 0.0, carbs = 0.0, sugars = 0.0, fibre = 0.0, protein = 0.0, salt = 1.0))
        val n = analyse(recipe(2, line(1, 100.0), line(2, 300.0)), listOf(oat, stock))
        assertEquals(400.0, n.rawGramsPerPortion * 2)
        assertEquals(200.0, n.rawGramsPerPortion)
        assertEquals((380.0 + 30.0) / 4.0, n.nutrition.per100.kcal, 1e-9)
        assertEquals((0.02 + 3.0) / 4.0, n.nutrition.per100.salt, 1e-9)
    }

    @Test
    fun `lights follow the per 100 g and per portion rules`() {
        // 100 g of something with 20 g fat per 100: red per 100 g, and 20 g a portion.
        val fatty = ing(1, per100 = Nutrition(kcal = 100.0, fat = 20.0, saturates = 1.0, carbs = 0.0, sugars = 4.0, fibre = 0.0, protein = 0.0, salt = 0.1))
        val n = analyse(recipe(1, line(1, 100.0)), listOf(fatty)).nutrition
        assertEquals(Light.RED, n.lights[Nutrient.FAT])
        assertEquals(Light.GREEN, n.lights[Nutrient.SATURATES])
        assertEquals(Light.GREEN, n.lights[Nutrient.SUGARS])
        assertEquals(Light.GREEN, n.lights[Nutrient.SALT])
        assertEquals(TrafficLights.coloured.toSet(), n.lights.keys)
    }

    @Test
    fun `unknown nutrition makes the totals at least`() {
        val known = ing(1, per100 = oats)
        val unknown = ing(2, per100 = Nutrition(kcal = 100.0))
        val none = ing(3, per100 = Nutrition())
        val n = analyse(recipe(1, line(1, 100.0), line(2, 100.0), line(3, 100.0), line(3, 10.0)), listOf(known, unknown, none)).nutrition
        assertTrue(n.atLeast)
        assertEquals(listOf(2L, 3L), n.unknownIngredients)
        assertEquals(Nutrient.entries.toSet(), n.unknownNutrients, "the ingredient that knows nothing leaves every nutrient open")
        val partly = analyse(recipe(1, line(1, 100.0), line(2, 100.0)), listOf(known, unknown)).nutrition
        assertEquals(Nutrient.entries.toSet() - Nutrient.KCAL, partly.unknownNutrients)
        assertEquals(listOf(2L), partly.unknownIngredients)
        assertEquals(380.0 + 100.0, n.perPortion.kcal, 1e-9)
        assertFalse(analyse(recipe(1, line(1, 100.0)), listOf(known)).nutrition.atLeast)
        // Added sugars aren't on UK labels, so not knowing them leaves only that row open.
        val labelOnly = analyse(recipe(1, line(1, 100.0)), listOf(known)).nutrition
        assertEquals(setOf(Nutrient.ADDED_SUGARS), labelOnly.unknownNutrients)
        assertEquals(listOf(1L), labelOnly.addedSugarsUnknown)
        val all = analyse(recipe(1, line(1, 100.0)), listOf(ing(1, per100 = oats.copy(addedSugars = 0.0)))).nutrition
        assertTrue(all.unknownNutrients.isEmpty())
        assertTrue(all.addedSugarsUnknown.isEmpty())
    }

    @Test
    fun `an uncounted ingredient with unknown nutrition does not matter`() {
        val known = ing(1, per100 = oats)
        val unknown = ing(2, per100 = Nutrition())
        val r = recipe(1, line(1, 100.0), line(2, 10.0, optional = true))
        assertFalse(analyse(r, listOf(known, unknown), countOptional = false).nutrition.atLeast)
        assertTrue(analyse(r, listOf(known, unknown), countOptional = true).nutrition.atLeast)
    }

    @Test
    fun `an empty recipe is all zeros`() {
        val n = analyse(recipe(2), emptyList())
        assertEquals(Money.ZERO, n.cost.whole)
        assertEquals(Money.ZERO, n.cost.perPortion)
        assertEquals(Money.ZERO, n.cost.fromEmptyCupboard)
        assertEquals(0.0, n.nutrition.perPortion.kcal)
        assertEquals(0.0, n.nutrition.per100.kcal)
        assertEquals(0.0, n.veg)
        assertEquals(0.0, n.rawGramsPerPortion)
        assertTrue(n.lines.isEmpty())
        assertTrue(n.breakdowns.getValue(BreakdownMetric.COST).top.isEmpty())
    }

    // ---- veg --------------------------------------------------------------------------------

    @Test
    fun `veg is 80 g portions of vegetables per serving`() {
        val carrot = ing(1, veg = VegKind.VEG)
        val potato = ing(2, veg = VegKind.NONE)
        val n = analyse(recipe(2, line(1, 320.0), line(2, 500.0)), listOf(carrot, potato))
        assertEquals(2.0, n.veg, 1e-9)
    }

    @Test
    fun `pulses count as one portion at most however much there is`() {
        val beans = ing(1, veg = VegKind.PULSE)
        val lentils = ing(2, veg = VegKind.PULSE)
        val carrot = ing(3, veg = VegKind.VEG)
        // 800 g of pulses between 2 is 400 g a portion: 5 portions, capped to 1.
        assertEquals(1.0, analyse(recipe(2, line(1, 800.0)), listOf(beans)).veg, 1e-9)
        // Under the cap they count in full: 80 g between 2 is half a portion.
        assertEquals(0.25, analyse(recipe(2, line(1, 40.0)), listOf(beans)).veg, 1e-9)
        // Two pulse ingredients are summed before the cap.
        assertEquals(0.625, analyse(recipe(2, line(1, 50.0), line(2, 50.0)), listOf(beans, lentils)).veg, 1e-9)
        assertEquals(1.0, analyse(recipe(2, line(1, 200.0), line(2, 200.0)), listOf(beans, lentils)).veg, 1e-9)
        // Vegetables are not capped.
        assertEquals(1.0 + 3.0, analyse(recipe(2, line(1, 800.0), line(3, 480.0)), listOf(beans, carrot)).veg, 1e-9)
    }

    @Test
    fun `the chilli's beans and lentils give exactly one portion`() {
        val n = StarterBook.numbers("chilli")
        // Tomatoes, onions, peppers and carrots are 1560 g: 4.875 portions between 4. Pulses add exactly 1.
        assertEquals(4.875 + 1.0, n.veg, 1e-9)
        val beans = StarterBook.ingredient("kidney_beans")
        val line = n.lines.first { it.ingredientId == beans.id }
        assertEquals(480.0, line.quantity)
        assertEquals(1.5, line.quantity / 4 / RecipeMaths.VEG_PORTION_GRAMS, "120 g a portion is 1.5 portions before the cap")
    }

    @Test
    fun `veg and nutrition numbers are shown to a label's precision`() {
        assertEquals("897", RecipeMaths.formatNutrient(Nutrient.KCAL, 896.6))
        assertEquals("898", RecipeMaths.formatNutrient(Nutrient.KCAL, 897.5))
        assertEquals("56.8", RecipeMaths.formatNutrient(Nutrient.PROTEIN, 56.76))
        assertEquals("5.0", RecipeMaths.formatNutrient(Nutrient.FAT, 5.0))
        assertEquals("0.1", RecipeMaths.formatNutrient(Nutrient.SALT, 0.14))
        assertEquals("5.9", RecipeMaths.formatVeg(5.875))
        assertEquals("1.0", RecipeMaths.formatVeg(1.0))
    }

    // ---- breakdown --------------------------------------------------------------------------

    @Test
    fun `the breakdown lists the five biggest and lumps the rest`() {
        val list = (1L..7L).map { ing(it, pack = 100.0, price = it * 10) }
        val lines = (1L..7L).map { line(it, 100.0) }
        val n = analyse(recipe(1, *lines.toTypedArray()), list)
        val cost = n.breakdowns.getValue(BreakdownMetric.COST)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), cost.top.map { it.ingredientId })
        assertEquals(280.0, cost.total, 1e-9)
        assertEquals(70.0 / 280, cost.top[0].fraction, 1e-9)
        val rest = assertNotNull(cost.rest)
        assertNull(rest.ingredientId)
        assertEquals(30.0, rest.value, 1e-9)
        assertEquals(2, cost.restCount)
        assertEquals(1.0, cost.top.sumOf { it.fraction } + rest.fraction, 1e-9)
    }

    @Test
    fun `with five or fewer ingredients there is no rest`() {
        val list = (1L..3L).map { ing(it, price = it * 100) }
        val n = analyse(recipe(2, line(1, 100.0), line(2, 100.0), line(3, 100.0)), list)
        val cost = n.breakdowns.getValue(BreakdownMetric.COST)
        assertEquals(3, cost.top.size)
        assertNull(cost.rest)
        assertEquals(0, cost.restCount)
        assertEquals(300.0, cost.total, 1e-9, "per portion: 600 p over 2")
    }

    @Test
    fun `an ingredient used on several lines is one share, and breakdowns cover cost, kcal, salt and protein`() {
        val a = ing(1, per100 = Nutrition(kcal = 100.0, fat = 0.0, saturates = 0.0, carbs = 0.0, sugars = 0.0, fibre = 0.0, protein = 10.0, salt = 1.0))
        val b = ing(2, per100 = Nutrition(kcal = 300.0, fat = 0.0, saturates = 0.0, carbs = 0.0, sugars = 0.0, fibre = 0.0, protein = 0.0, salt = 0.0))
        val n = analyse(recipe(1, line(1, 50.0), line(2, 50.0), line(1, 50.0)), listOf(a, b))
        assertEquals(setOf(BreakdownMetric.COST, BreakdownMetric.KCAL, BreakdownMetric.SALT, BreakdownMetric.PROTEIN), n.breakdowns.keys)
        val kcal = n.breakdowns.getValue(BreakdownMetric.KCAL)
        assertEquals(listOf(2L, 1L), kcal.top.map { it.ingredientId })
        assertEquals(150.0, kcal.top[0].value, 1e-9)
        assertEquals(100.0, kcal.top[1].value, 1e-9)
        assertEquals(listOf(1L), n.breakdowns.getValue(BreakdownMetric.SALT).top.map { it.ingredientId })
        assertEquals(1.0, n.breakdowns.getValue(BreakdownMetric.PROTEIN).top.single().fraction, 1e-9)
        assertEquals(listOf(1L, 2L), n.breakdowns.getValue(BreakdownMetric.COST).top.map { it.ingredientId })
    }

    @Test
    fun `the starter breakdown of the stir-fry is largest first`() {
        val cost = StarterBook.numbers("stir-fry").breakdowns.getValue(BreakdownMetric.COST)
        assertEquals(5, cost.top.size)
        assertTrue(cost.top.zipWithNext().all { (a, b) -> a.value >= b.value })
        assertEquals(StarterBook.ingredient("chicken_breast").id, cost.top.first().ingredientId)
        assertNotNull(cost.rest)
        assertEquals(StarterBook.numbers("stir-fry").cost.totalPence / 2, cost.total, 1e-6)
    }
}
