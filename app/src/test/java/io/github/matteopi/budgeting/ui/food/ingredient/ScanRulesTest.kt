package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.LabelReading
import io.github.matteopi.budgeting.core.recipes.LabelValue
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.NutritionLabel
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.OffDraft
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.VegKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Putting a label reading, an Open Food Facts draft or a food of the food list into the ingredient editor's form (docs/PLAN.md §11.5, §11.12). */
class ScanRulesTest {

    @Test
    fun aLabelFillsTheValuesItFoundAndKeepsTheRest() {
        val form = IngredientForm(name = "Mince", packSizeText = "500", nutrition = mapOf(Nutrient.FIBRE to "0.2"))
        val reading = NutritionLabel.parseText(listOf("Per 100g", "Energy 520kJ/124kcal", "Fat 5.0g", "Salt 0.15g"))
        val filled = ScanRules.applyLabel(form, reading)
        assertEquals("124", filled.nutrition[Nutrient.KCAL])
        assertEquals("5", filled.nutrition[Nutrient.FAT])
        assertEquals("0.15", filled.nutrition[Nutrient.SALT])
        assertEquals("0.2", filled.nutrition[Nutrient.FIBRE])
        assertEquals(NutritionSource.LABEL, filled.nutritionSource)
        assertEquals(BaseUnit.G, filled.unit)
    }

    @Test
    fun aPer100mlLabelMakesANewIngredientMillilitres() {
        val reading = LabelReading(mapOf(Nutrient.KCAL to LabelValue(46.0)), columnFound = true, unit = BaseUnit.ML)
        assertEquals(BaseUnit.ML, ScanRules.applyLabel(IngredientForm(), reading).unit)
        // An ingredient that already has its pack size keeps its unit.
        assertEquals(BaseUnit.G, ScanRules.applyLabel(IngredientForm(packSizeText = "250"), reading).unit)
    }

    @Test
    fun aDraftFillsWhatIsBlankAndTheNutrition() {
        val draft = OffDraft(
            barcode = "5000436589458",
            name = "Tesco Chopped Tomatoes",
            packSize = 400.0,
            unit = BaseUnit.G,
            quantityText = "400 g",
            per100 = Nutrition(kcal = 21.0, carbs = 3.5, salt = 0.03),
        )
        val fresh = ScanRules.applyDraft(IngredientForm(), draft)
        assertEquals("Tesco Chopped Tomatoes", fresh.name)
        assertEquals("Tesco Chopped Tomatoes", fresh.productName)
        assertEquals("5000436589458", fresh.barcode)
        assertEquals("400", fresh.packSizeText)
        assertEquals("400 g", fresh.packLabel)
        assertEquals("21", fresh.nutrition[Nutrient.KCAL])
        assertEquals("0.03", fresh.nutrition[Nutrient.SALT])
        assertEquals(NutritionSource.BARCODE, fresh.nutritionSource)

        val mine = IngredientForm(name = "Chopped tomatoes (tin)", packSizeText = "400", packLabel = "400 g tin")
        val kept = ScanRules.applyDraft(mine, draft)
        assertEquals("Chopped tomatoes (tin)", kept.name)
        assertEquals("400 g tin", kept.packLabel)
        assertEquals("5000436589458", kept.barcode)
    }

    @Test
    fun theFoodListFillsTheEmptyFieldsAndThePriceOnlyWhenThereIsNone() {
        val chickpeas = FoodDatabase.byKey("chickpeas")!!
        val fresh = ScanRules.applyFood(IngredientForm(name = "Chickpeas"), chickpeas)
        assertEquals("121", fresh.nutrition[Nutrient.KCAL])
        assertEquals("0", fresh.nutrition[Nutrient.ADDED_SUGARS])
        assertEquals(NutritionSource.ESTIMATE, fresh.nutritionSource)
        assertEquals("0.41", fresh.priceText)
        assertEquals(LocalDate.of(2026, 10, 22), fresh.priceDate)
        assertEquals("240", fresh.packSizeText)
        assertEquals("400 g tin (240 g drained)", fresh.packLabel)
        assertEquals("Tesco", fresh.store)
        assertEquals(VegKind.PULSE, fresh.vegKind)
        assertEquals(Shelf.CUPBOARD, fresh.shelf)
        assertEquals(listOf("tin" to "240"), fresh.portions.map { it.name to it.amountText })
        assertTrue(ScanRules.hasNoNutrition(IngredientForm()))
        assertFalse(ScanRules.hasNoNutrition(fresh))

        // What the person typed stays: their price and pack, their fat value and their own "tin" portion.
        val mine = IngredientForm(
            name = "Chickpeas",
            packSizeText = "400",
            priceText = "0.55",
            nutrition = mapOf(Nutrient.FAT to "3"),
            shelf = Shelf.OTHER,
            portions = listOf(PortionRow(1, "tins", "230")),
            nextKey = 2,
        )
        val kept = ScanRules.applyFood(mine, chickpeas)
        assertEquals("0.55", kept.priceText)
        assertEquals("400", kept.packSizeText)
        assertEquals("3", kept.nutrition[Nutrient.FAT])
        assertEquals("121", kept.nutrition[Nutrient.KCAL])
        assertEquals(NutritionSource.TYPED, kept.nutritionSource, "some values were the person's")
        assertEquals(listOf("tins" to "230"), kept.portions.map { it.name to it.amountText })

        // A millilitre ingredient doesn't take a price per gram.
        assertEquals("", ScanRules.applyFood(IngredientForm(name = "Chickpeas", unit = BaseUnit.ML), chickpeas).priceText)
    }

    @Test
    fun theLabelReviewRows() {
        val reading = NutritionLabel.parseText(listOf("Per 100g", "Energy 1690kJ/411kcal", "Fat <0.5g", "of which saturates trace"))
        assertEquals(
            listOf(
                Triple("Energy", "411 kcal", null),
                Triple("Fat", "0.5 g", "less than"),
                Triple("Saturates", "0 g", "trace"),
            ),
            ScanRules.labelRows(reading),
        )
    }
}
