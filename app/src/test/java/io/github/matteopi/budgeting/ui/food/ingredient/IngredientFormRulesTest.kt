package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.core.recipes.Shelf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngredientFormRulesTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val starter = RecipeBundle.starter().toModels().ingredients

    private fun valid() = IngredientForm(name = "Halloumi", packSizeText = "225")

    @Test
    fun everyStarterIngredientSurvivesTheFormUnchanged() {
        for (ingredient in starter) {
            val form = IngredientFormRules.fromIngredient(ingredient)
            assertFalse(IngredientFormRules.errors(form).any, ingredient.name)
            assertEquals(ingredient, IngredientFormRules.build(form, ingredient, today), ingredient.name)
        }
    }

    @Test
    fun aNewIngredientNeedsANameAndAPackSizeAboveZero() {
        assertTrue(IngredientFormRules.errors(IngredientForm()).name)
        assertTrue(IngredientFormRules.errors(IngredientForm()).packSize)
        assertTrue(IngredientFormRules.errors(valid().copy(packSizeText = "0")).packSize)
        assertTrue(IngredientFormRules.errors(valid().copy(packSizeText = "-5")).packSize)
        assertTrue(IngredientFormRules.errors(valid().copy(packSizeText = "lots")).packSize)
        assertFalse(IngredientFormRules.errors(valid()).any)
        assertEquals("An ingredient needs a name.", IngredientFormRules.errors(IngredientForm()).message)
        assertNull(IngredientFormRules.build(IngredientForm(), null, today))
    }

    @Test
    fun priceAndNutritionMustBeZeroOrMore() {
        assertTrue(IngredientFormRules.errors(valid().copy(priceText = "cheap")).price)
        assertTrue(IngredientFormRules.errors(valid().copy(priceText = "-1")).price)
        assertFalse(IngredientFormRules.errors(valid().copy(priceText = "")).price)
        assertFalse(IngredientFormRules.errors(valid().copy(priceText = "£2.50")).price)

        val bad = valid().copy(nutrition = mapOf(Nutrient.FAT to "-1", Nutrient.SALT to "0.5", Nutrient.KCAL to "x"))
        assertEquals(setOf(Nutrient.FAT, Nutrient.KCAL), IngredientFormRules.errors(bad).nutrition)
        assertEquals("Nutrition values must be numbers of zero or more.", IngredientFormRules.errors(bad).message)
        assertFalse(IngredientFormRules.errors(valid().copy(nutrition = mapOf(Nutrient.FAT to "0", Nutrient.SALT to "0,5"))).any)
    }

    @Test
    fun aPortionNeedsANameAndAnAmountAboveZeroUnlessItIsBlank() {
        val rows = listOf(
            PortionRow(1, "each", "150"),
            PortionRow(2, "", ""),
            PortionRow(3, "large", ""),
            PortionRow(4, "", "5"),
            PortionRow(5, "tiny", "0"),
        )
        assertEquals(setOf(3L, 4L, 5L), IngredientFormRules.errors(valid().copy(portions = rows)).portions)

        val ok = IngredientFormRules.build(valid().copy(portions = rows.take(2)), null, today)
        assertEquals(listOf("each"), ok?.portions?.map { it.name })
    }

    @Test
    fun aPriceTypedWithoutADateIsDatedToday() {
        val built = IngredientFormRules.build(valid().copy(priceText = "2.50"), null, today)
        assertEquals(Money(250), built?.price)
        assertEquals(today, built?.priceDate)

        val dated = IngredientFormRules.build(valid().copy(priceText = "2.50", priceDate = LocalDate.of(2026, 9, 1)), null, today)
        assertEquals(LocalDate.of(2026, 9, 1), dated?.priceDate)

        val none = IngredientFormRules.build(valid().copy(priceText = "", priceDate = LocalDate.of(2026, 9, 1)), null, today)
        assertNull(none?.price)
        assertNull(none?.priceDate)
    }

    @Test
    fun textIsTrimmedAndBlankOptionsBecomeNull() {
        val built = IngredientFormRules.build(
            valid().copy(
                name = "  Halloumi ",
                packLabel = " 225 g block ",
                store = " ",
                productName = " Cypressa halloumi ",
                sourceUrl = "",
                barcode = "50 1234-5678",
                unit = BaseUnit.ML,
                shelf = Shelf.DAIRY,
                note = " squeaky ",
            ),
            null,
            today,
        )
        assertNotNull(built)
        assertEquals("Halloumi", built.name)
        assertEquals("225 g block", built.packLabel)
        assertNull(built.store)
        assertEquals("Cypressa halloumi", built.productName)
        assertNull(built.sourceUrl)
        assertEquals("5012345678", built.barcode)
        assertEquals(BaseUnit.ML, built.unit)
        assertEquals(Shelf.DAIRY, built.shelf)
        assertEquals("squeaky", built.note)
    }

    @Test
    fun editingKeepsWhatTheFormDoesNotShow() {
        val base = Ingredient(id = 7, name = "Old", packSize = 1.0, archived = true, createdAtEpochMillis = 1234)
        val built = IngredientFormRules.build(valid(), base, today)
        assertEquals(7L, built?.id)
        assertTrue(built?.archived == true)
        assertEquals(1234L, built?.createdAtEpochMillis)
    }

    @Test
    fun theHelpersParseAndFormat() {
        assertEquals("5012345678", IngredientFormRules.digitsOnly("50-1234 5678x"))
        assertEquals(1.5, IngredientFormRules.parseNumber(" 1,5 "))
        assertNull(IngredientFormRules.parseNumber("-1"))
        assertNull(IngredientFormRules.parseNumber("NaN"))
        assertEquals("0.99", IngredientFormRules.priceInput(Money(99)))
        assertEquals("1.50", IngredientFormRules.priceInput(Money(150)))
        assertEquals("12.00", IngredientFormRules.priceInput(Money(1200)))
        assertEquals(9, IngredientFormRules.NUTRIENT_ORDER.size)
        assertEquals(Nutrient.ADDED_SUGARS, IngredientFormRules.NUTRIENT_ORDER[5], "added sugars come after the sugars")
    }
}
