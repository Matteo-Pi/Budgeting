package io.github.matteopi.budgeting.ui.food.diary

import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.Intake
import io.github.matteopi.budgeting.core.nutrition.IntakeKey
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.ui.food.log.LogFoodViewModel
import io.github.matteopi.budgeting.widget.food.FoodWidgetState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The diary's, the kitchen's and the Food widget's pure helpers (docs/PLAN.md §14.5, §14.6). */
class DiaryMappingTest {
    private val day = LocalDate.of(2026, 10, 23)
    private val bread = Ingredient(id = 3, name = "White bread", portions = listOf(Portion("slice", 40.0)))

    private fun row(id: Long, meal: MealSlot, kcal: Double, kind: LogKind = LogKind.FOOD, sideOf: Long? = null, amount: Double = 1.0) =
        LogItem(
            id = id, date = day, meal = meal, eatenAtEpochMillis = id, kind = kind, name = "Row $id", amount = amount,
            sideOf = sideOf, costPence = 100.0, intake = Intake.of(mapOf(IntakeKey.KCAL to kcal, IntakeKey.PROTEIN to 10.0)),
        )

    @Test
    fun aNewAmountScalesTheFrozenNumbersAndRewritesTheText() {
        val toast = row(1, MealSlot.BREAKFAST, 282.0, amount = 120.0).copy(amountText = "3 slices", ingredientId = 3)
        val two = DiaryMapping.withAmount(toast, 80.0, bread)
        assertEquals("2 slices", two.amountText)
        assertEquals(188.0, two.intake.kcal, 1e-9)
        assertEquals(200.0 / 3, two.costPence!!, 1e-9)
        val portion = row(2, MealSlot.DINNER, 600.0, kind = LogKind.BATCH)
        assertEquals("1½ portions", DiaryMapping.withAmount(portion, 1.5, null).amountText)
        assertEquals(900.0, DiaryMapping.withAmount(portion, 1.5, null).intake.kcal, 1e-9)
        assertEquals(toast, DiaryMapping.withAmount(toast, 0.0, bread), "nothing changes for no amount")
        assertEquals("3 slices · 10 g protein", DiaryMapping.detail(toast))
    }

    @Test
    fun mealsHoldTheirRowsWithSidesUnderTheirPortion() {
        val items = listOf(
            row(1, MealSlot.DINNER, 600.0, kind = LogKind.BATCH),
            row(2, MealSlot.DINNER, 270.0, sideOf = 1),
            row(3, MealSlot.BREAKFAST, 300.0),
        )
        val meals = DiaryViewModel.meals(items)
        assertEquals(MealSlot.entries, meals.map { it.meal })
        assertEquals(listOf(3L), meals[0].rows.map { it.item.id })
        assertEquals(listOf(1L, 2L), meals[2].rows.map { it.item.id })
        assertEquals(listOf(false, true), meals[2].rows.map { it.isSide })
        assertEquals(870.0, meals[2].kcal, 1e-9)
        assertEquals(0, meals[1].rows.size)
    }

    @Test
    fun theKitchenSaysWhereThePortionsAre() {
        val batch = Batch(name = "Chilli", madeOn = day, servings = 4.0, portions = 6.0, fridgeLeft = 3.5, freezerLeft = 2.0)
        assertEquals("3½ in the fridge, 2 in the freezer", DiaryMapping.leftText(batch))
        assertEquals("None left", DiaryMapping.leftText(batch.copy(fridgeLeft = 0.0, freezerLeft = 0.0)))
        assertEquals("Today · Fri 23 Oct", dayTitle(day, day))
        assertEquals("Yesterday · Thu 22 Oct", dayTitle(day.minusDays(1), day))
        assertEquals("£1.20", penceText(119.6))
        assertEquals("no price", penceText(null))
    }

    @Test
    fun sidesStepByHalfAPortionOr25Grams() {
        assertEquals(20.0, LogFoodViewModel.stepOf(bread), 1e-9)
        assertEquals(25.0, LogFoodViewModel.stepOf(Ingredient(name = "Rice")), 1e-9)
        assertEquals("i3", LogFoodViewModel.keyOf(bread, null))
        assertEquals("frice", LogFoodViewModel.keyOf(Ingredient(name = "Rice"), "rice"))
    }

    @Test
    fun theWidgetShowsTodaysKcalAndUpToThreeBatches() {
        val batches = (1L..4L).map { Batch(id = it, name = "Batch $it", madeOn = day, servings = 2.0, portions = 2.0, fridgeLeft = 1.0, freezerLeft = 0.0, eatBy = day.plusDays(it)) }
        val state = FoodWidgetState.of(listOf(row(1, MealSlot.LUNCH, 640.0), row(2, MealSlot.DINNER, 600.0)), batches, 2000.0, 50.0)
        assertEquals("1,240 of 2,000 kcal", state.kcalLine)
        assertEquals("Protein 20 of 50 g", state.proteinLine)
        assertEquals(listOf(1L, 2L, 3L), state.batches.map { it.id })
        assertEquals("1 in the fridge", state.batches.first().detail)
    }
}
