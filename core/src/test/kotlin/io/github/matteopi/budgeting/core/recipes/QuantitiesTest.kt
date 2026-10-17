package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuantitiesTest {

    @Test
    fun `measure words`() {
        assertEquals(Measure.G, Quantities.measureOf("g"))
        assertEquals(Measure.G, Quantities.measureOf("Grams"))
        assertEquals(Measure.KG, Quantities.measureOf("kg"))
        assertEquals(Measure.ML, Quantities.measureOf("ML"))
        assertEquals(Measure.L, Quantities.measureOf("litres"))
        assertEquals(Measure.TSP, Quantities.measureOf("tsp."))
        assertEquals(Measure.TBSP, Quantities.measureOf("tablespoons"))
        assertEquals(Measure.PINCH, Quantities.measureOf("pinches"))
        assertEquals(Measure.OZ, Quantities.measureOf("oz"))
        assertEquals(Measure.LB, Quantities.measureOf("lbs"))
        assertEquals(Measure.CUP, Quantities.measureOf("cups"))
        assertNull(Quantities.measureOf("large"))
        assertNull(Quantities.measureOf("garlic"))
        assertNull(Quantities.measureOf(""))
    }

    @Test
    fun `the fixed conversions`() {
        val flour = Ingredient(name = "Flour")
        assertEquals(1000.0, Quantities.toBase(1.0, Measure.KG, flour).quantity)
        assertEquals(28.35, Quantities.toBase(1.0, Measure.OZ, flour).quantity, 1e-9)
        assertEquals(453.6, Quantities.toBase(1.0, Measure.LB, flour).quantity, 1e-9)
        assertEquals(0.3, Quantities.toBase(1.0, Measure.PINCH, flour).quantity, 1e-9)
        val milk = Ingredient(name = "Milk", unit = BaseUnit.ML)
        assertEquals(5.0, Quantities.toBase(1.0, Measure.TSP, milk).quantity)
        assertEquals(15.0, Quantities.toBase(1.0, Measure.TBSP, milk).quantity)
        assertEquals(240.0, Quantities.toBase(1.0, Measure.CUP, milk).quantity)
        assertEquals(2000.0, Quantities.toBase(2.0, Measure.L, milk).quantity)
    }

    @Test
    fun `portions match singular or plural`() {
        val garlic = Ingredient(name = "Garlic", portions = listOf(Portion("clove", 5.0), Portion("leaves", 1.0)))
        assertEquals(5.0, Quantities.portion(garlic, "cloves")?.amount)
        assertEquals(5.0, Quantities.portion(garlic, "Clove")?.amount)
        assertEquals(1.0, Quantities.portion(garlic, "leaf")?.amount)
        assertNull(Quantities.portion(garlic, "bulb"))
        assertEquals(5.0, Quantities.portionNamedIn(garlic, listOf("fresh", "garlic", "cloves"))?.amount)
        assertNull(Quantities.portionNamedIn(garlic, listOf("fresh", "garlic")))
    }

    @Test
    fun `stems are the same for singular and plural`() {
        val pairs = listOf(
            "clove" to "cloves", "tomato" to "tomatoes", "potato" to "potatoes", "chilli" to "chillies",
            "leaf" to "leaves", "berry" to "berries", "peach" to "peaches", "slice" to "slices",
            "onion" to "onions", "pea" to "peas", "bird's" to "birds", "purée" to "puree",
        )
        for ((a, b) in pairs) assertEquals(Quantities.stem(a), Quantities.stem(b), "$a / $b")
        assertEquals("asparagus", Quantities.stem("asparagus"))
        assertEquals("hummus", Quantities.stem("hummus"))
        assertEquals("cress", Quantities.stem("cress"))
    }

    @Test
    fun `words are lower case without accents or punctuation`() {
        assertEquals(listOf("tomato", "puree", "2", "tbsp"), Quantities.words("Tomato Purée, 2 tbsp!"))
        assertEquals(listOf("birds", "eye"), Quantities.words("bird's-eye"))
    }

    @Test
    fun `quantities are shown to a useful precision`() {
        assertEquals("150 g", Quantities.format(150.0, BaseUnit.G))
        assertEquals("7.5 g", Quantities.format(7.5, BaseUnit.G))
        assertEquals("0.3 g", Quantities.format(0.3, BaseUnit.G))
        assertEquals("225 g", Quantities.format(225.0, BaseUnit.G))
        assertEquals("1.2 kg", Quantities.format(1200.0, BaseUnit.G))
        assertEquals("1 kg", Quantities.format(1000.0, BaseUnit.G))
        assertEquals("250 ml", Quantities.format(250.0, BaseUnit.ML))
        assertEquals("1.5 l", Quantities.format(1500.0, BaseUnit.ML))
        assertEquals("10 g", Quantities.format(9.96, BaseUnit.G))
    }

    @Test
    fun `plain numbers round half up without trailing zeros`() {
        assertEquals("7.5", Quantities.plain(7.5, 1))
        assertEquals("7", Quantities.plain(7.0, 1))
        assertEquals("0.13", Quantities.plain(0.125, 2))
        assertEquals("1.01", Quantities.plain(1.005, 2))
        assertEquals("0", Quantities.plain(-0.001, 1))
        assertEquals("300", Quantities.plain(300.0, 0))
    }

    @Test
    fun `counts use fraction glyphs`() {
        assertEquals("1½", Quantities.formatCount(1.5))
        assertEquals("½", Quantities.formatCount(0.5))
        assertEquals("¼", Quantities.formatCount(0.25))
        assertEquals("2¾", Quantities.formatCount(2.75))
        assertEquals("3", Quantities.formatCount(3.0))
        assertEquals("1⅓", Quantities.formatCount(1.0 / 3 + 1))
        assertEquals("2", Quantities.formatCount(1.999999))
        assertEquals("1.8", Quantities.formatCount(1.8))
    }

    @Test
    fun `rounding is half up`() {
        assertEquals(3L, Quantities.roundHalfUp(2.5))
        assertEquals(2L, Quantities.roundHalfUp(2.49))
        assertEquals(1L, Quantities.roundHalfUp(0.5))
        assertEquals(0L, Quantities.roundHalfUp(0.0))
    }
}
