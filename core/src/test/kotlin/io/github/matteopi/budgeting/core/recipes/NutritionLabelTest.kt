package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Typed UK nutrition labels in the common layouts, read the way OCR rows arrive (docs/PLAN.md §11.5). */
class NutritionLabelTest {

    private fun read(text: String) = NutritionLabel.parseText(text.trimIndent().lines())

    private fun LabelReading.value(nutrient: Nutrient): Double? = values[nutrient]?.value

    @Test
    fun per100gFirstWithAPortionColumnAndReferenceIntakes() {
        val reading = read(
            """
            NUTRITION
            Typical values Per 100g Per ½ pack (150g) %RI*
            Energy 540kJ / 129kcal 810kJ / 194kcal 10%
            Fat 5.0g 7.5g 11%
            of which saturates 2.2g 3.3g 17%
            Carbohydrate 0g 0g
            of which sugars 0g 0g 0%
            Fibre 0g 0g
            Protein 21.4g 32.1g 64%
            Salt 0.15g 0.23g 4%
            *Reference intake of an average adult (8400kJ/2000kcal)
            """,
        )
        assertTrue(reading.columnFound)
        assertEquals(BaseUnit.G, reading.unit)
        assertEquals(129.0, reading.value(Nutrient.KCAL))
        assertEquals(5.0, reading.value(Nutrient.FAT))
        assertEquals(2.2, reading.value(Nutrient.SATURATES))
        assertEquals(0.0, reading.value(Nutrient.CARBS))
        assertEquals(0.0, reading.value(Nutrient.SUGARS))
        assertEquals(0.0, reading.value(Nutrient.FIBRE))
        assertEquals(21.4, reading.value(Nutrient.PROTEIN))
        assertEquals(0.15, reading.value(Nutrient.SALT))
        assertTrue(reading.values.values.all { it.confidence == 1.0 })
    }

    @Test
    fun perServingFirstThenPer100g() {
        val reading = read(
            """
            Typical values Per serving (30g) Per 100g
            Energy 486kJ/115kcal 1620kJ/384kcal
            Fat 0.6g 2.0g
            of which saturates 0.1g 0.4g
            Carbohydrate 24.0g 80.0g
            of which sugars 1.2g 4.0g
            Fibre 0.9g 3.0g
            Protein 2.1g 7.0g
            Salt 0.05g 0.18g
            """,
        )
        assertEquals(384.0, reading.value(Nutrient.KCAL))
        assertEquals(2.0, reading.value(Nutrient.FAT))
        assertEquals(80.0, reading.value(Nutrient.CARBS))
        assertEquals(0.18, reading.value(Nutrient.SALT))
    }

    @Test
    fun energyOnTwoRowsLessThanAndTrace() {
        val reading = read(
            """
            Per 100ml
            Energy 1690kJ
            411kcal
            Fat <0.5g
            of which saturates trace
            of which mono-unsaturates 0.2g
            Carbohydrates 100g
            of which sugars 100g
            Protein <0.5g
            Salt 0.01g
            """,
        )
        assertEquals(BaseUnit.ML, reading.unit)
        assertEquals(411.0, reading.value(Nutrient.KCAL))
        val fat = reading.values.getValue(Nutrient.FAT)
        assertEquals(0.5, fat.value)
        assertTrue(fat.lessThan)
        assertEquals(0.0, reading.value(Nutrient.SATURATES))
        assertTrue(reading.values.getValue(Nutrient.SATURATES).lessThan)
        assertEquals(100.0, reading.value(Nutrient.CARBS))
        assertEquals(100.0, reading.value(Nutrient.SUGARS))
        assertNull(reading.value(Nutrient.FIBRE))
    }

    @Test
    fun energyInKjAndKcalRowsWithTheUnitInTheName() {
        val reading = read(
            """
            Typical values per 100g per serving
            Energy (kJ) 1543 386
            Energy (kcal) 368 92
            Fat (g) 1.5 0.4
            Salt (g) 1.1 0.3
            """,
        )
        assertEquals(368.0, reading.value(Nutrient.KCAL))
        assertEquals(1.5, reading.value(Nutrient.FAT))
        assertEquals(1.1, reading.value(Nutrient.SALT))
    }

    @Test
    fun onlyKjIsConvertedAndSodiumBecomesSalt() {
        val reading = read(
            """
            Per 100g
            Energy 418kJ
            Protein 3.4g
            Sodium 0.04g
            """,
        )
        assertEquals(418.0 / 4.184, reading.value(Nutrient.KCAL)!!, 1e-9)
        assertEquals(0.1, reading.value(Nutrient.SALT)!!, 1e-9)
    }

    @Test
    fun withNoHeadingTheFirstColumnIsTakenWithLessConfidence() {
        val reading = read(
            """
            Energy 340kJ/81kcal
            Fat 3,5g
            Salt 0,2g
            """,
        )
        assertFalse(reading.columnFound)
        assertNull(reading.unit)
        assertEquals(81.0, reading.value(Nutrient.KCAL))
        assertEquals(3.5, reading.value(Nutrient.FAT))
        assertEquals(0.2, reading.value(Nutrient.SALT))
        assertTrue(reading.values.values.all { it.confidence < 1.0 })
        assertEquals(81.0, reading.toNutrition().kcal)
        assertNull(reading.toNutrition().protein)
    }

    @Test
    fun textWithNoLabelGivesNothing() {
        assertTrue(read("Tesco\nMilk 1.20\nTOTAL 1.20").isEmpty)
    }
}
