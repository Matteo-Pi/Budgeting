package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The checkpoint table of docs/PLAN.md §11.7, worked out independently from the same data. */
class StarterCheckpointsTest {

    private class Row(
        val key: String,
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

    private val table = listOf(
        Row("stir-fry", true, 309, 618, 2056, 804, 56.8, 5.84, 6.06, "RGGR"),
        Row("stir-fry", false, 301, 601, 1891, 793, 56.7, 5.12, 6.06, "RGGR"),
        Row("traybake", true, 383, 765, 2310, 768, 54.9, 2.61, 5.75, "RGGR"),
        Row("traybake", false, 305, 610, 2025, 734, 52.6, 2.59, 4.81, "RGGR"),
        Row("braise", true, 372, 745, 2932, 891, 57.0, 5.14, 3.81, "RRGR"),
        Row("braise", false, 364, 729, 2773, 857, 56.5, 5.13, 2.81, "RRGR"),
        Row("chilli", true, 254, 1017, 3178, 897, 54.0, 1.78, 5.88, "GGGG"),
        Row("chilli", false, 253, 1011, 2898, 897, 54.0, 1.41, 5.88, "GGGG"),
    )

    private fun letter(light: Light?) = when (light) {
        Light.GREEN -> 'G'
        Light.AMBER -> 'A'
        Light.RED -> 'R'
        null -> '?'
    }

    @Test
    fun `the starter book has four recipes and forty ingredients`() {
        assertEquals(4, StarterBook.recipes.size)
        assertEquals(40, StarterBook.ingredients.size)
    }

    @Test
    fun `every row of the checkpoint table`() {
        for (row in table) {
            val label = "${row.key} (optional extras ${if (row.optional) "on" else "off"})"
            val n = StarterBook.numbers(row.key, countOptional = row.optional)
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
            assertTrue(!n.nutrition.atLeast, "$label: every nutrition value is known")
        }
    }

    @Test
    fun `protein and salt agree with the table once rounded to its precision`() {
        for (row in table) {
            val n = StarterBook.numbers(row.key, countOptional = row.optional)
            assertEquals(row.protein, Quantities.plain(n.nutrition.perPortion.protein, 1).toDouble(), 1e-9, "${row.key} protein to 1 dp")
            assertEquals(row.salt, Quantities.plain(n.nutrition.perPortion.salt, 2).toDouble(), 1e-9, "${row.key} salt to 2 dp")
        }
    }
}
