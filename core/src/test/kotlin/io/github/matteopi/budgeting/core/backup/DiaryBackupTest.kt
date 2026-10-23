package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.FoodDay
import io.github.matteopi.budgeting.core.nutrition.Intake
import io.github.matteopi.budgeting.core.nutrition.IntakeKey
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.nutrition.Micros
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Recipe
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Backup version 10: the kitchen's batches, the food diary, its days and the ingredients' vitamins (docs/PLAN.md §14.3). */
class DiaryBackupTest {

    private val day = LocalDate.of(2026, 10, 23)
    private val intake = Intake.of(
        mapOf(IntakeKey.KCAL to 550.0, IntakeKey.PROTEIN to 41.5, IntakeKey.IRON to 4.2),
        vegGrams = 150.0,
        pulseGrams = 60.0,
        unknown = setOf(IntakeKey.VITAMIN_D, IntakeKey.ADDED_SUGARS),
    )

    private fun data(): BackupData {
        val rice = Ingredient(id = 1, name = "Rice", micros = Micros(iron = 0.8, zinc = 1.1, vitaminA = 0.0))
        val chilli = Recipe(id = 1, title = "Chilli", serves = 4)
        val batch = Batch(
            id = 1, recipeId = 1, name = "Chilli", madeOn = day, servings = 8.0, portions = 6.0, fridgeLeft = 3.5,
            freezerLeft = 2.0, frozenOn = day, eatBy = day.plusDays(2), wasted = 0.5, costPence = 118.4, note = "Extra spicy",
            createdAtEpochMillis = 10, intake = intake,
        )
        val portion = LogItem(
            id = 1, date = day, meal = MealSlot.DINNER, eatenAtEpochMillis = 20, kind = LogKind.BATCH, name = "Chilli",
            amount = 1.0, amountText = "1 portion", recipeId = 1, batchId = 1, costPence = 118.4, createdAtEpochMillis = 21, intake = intake,
        )
        val side = LogItem(
            id = 2, date = day, meal = MealSlot.DINNER, eatenAtEpochMillis = 20, kind = LogKind.FOOD, name = "Rice",
            amount = 75.0, amountText = "75 g", ingredientId = 1, sideOf = 1, costPence = null, createdAtEpochMillis = 22,
            intake = intake * 0.5,
        )
        return BackupData(
            ingredients = listOf(rice),
            recipes = listOf(chilli),
            mealBatches = listOf(batch),
            foodLog = listOf(portion, side),
            foodDays = listOf(FoodDay(day, 1250, "Gym day")),
        )
    }

    private fun encode(data: BackupData) = BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now"))

    private fun failureOf(data: BackupData): List<String> = assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(encode(data))).errors

    @Test
    fun `the diary, the kitchen and the vitamins round trip`() {
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(encode(data()))).data
        val original = data()
        assertEquals(original.mealBatches, back.mealBatches)
        assertEquals(original.foodLog, back.foodLog)
        assertEquals(original.foodDays, back.foodDays)
        assertEquals(original.ingredients.single().micros, back.ingredients.single().micros)
    }

    @Test
    fun `a version 9 file restores with no diary and unknown vitamins`() {
        val json = """
            {"format":"budgeting-backup","version":9,"exportedAt":"now",
             "ingredients":[{"id":1,"name":"Onion","unit":"G","packSize":1000.0,"packLabel":"1 kg","pricePence":95,
               "priceDate":null,"store":null,"productId":null,"productName":null,"sourceUrl":null,"barcode":null,
               "kcal":36.0,"fat":null,"saturates":null,"carbs":null,"sugars":5.6,"fibre":null,"protein":null,"salt":null,
               "nutritionSource":"TYPED","vegKind":"VEG","shelf":"FRUIT_VEG","note":"","archived":false,"createdAtEpochMillis":1}],
             "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}
        """.trimIndent()
        val data = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertTrue(data.ingredients.single().micros.isEmpty)
        assertTrue(data.foodLog.isEmpty() && data.mealBatches.isEmpty() && data.foodDays.isEmpty())
    }

    @Test
    fun `an intake key this version doesn't know is left out`() {
        val json = encode(data()).replace("\"KCAL\"", "\"KCAL\": 550.0, \"OMEGA_3\"")
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertEquals(550.0, back.foodLog.first().intake.kcal)
    }

    @Test
    fun `decode rejects diary rows and batches that point at nothing or break a rule`() {
        val data = data()
        val row = data.foodLog.first()
        val batch = data.mealBatches.single()
        assertTrue(failureOf(data.copy(foodLog = listOf(row.copy(recipeId = 9)))).any { it.contains("missing recipe 9") })
        assertTrue(failureOf(data.copy(foodLog = listOf(row.copy(batchId = 9)))).any { it.contains("missing kitchen batch 9") })
        assertTrue(failureOf(data.copy(foodLog = listOf(row.copy(ingredientId = 9)))).any { it.contains("missing ingredient 9") })
        assertTrue(failureOf(data.copy(foodLog = listOf(row.copy(sideOf = 9)))).any { it.contains("side of a missing row 9") })
        assertTrue(failureOf(data.copy(foodLog = listOf(row, row))).any { it.contains("same id") })
        assertTrue(failureOf(data.copy(foodLog = listOf(row.copy(amount = -1.0)))).any { it.contains("below zero") })
        assertTrue(failureOf(data.copy(mealBatches = listOf(batch.copy(recipeId = 9)))).any { it.contains("missing recipe 9") })
        assertTrue(failureOf(data.copy(mealBatches = listOf(batch.copy(fridgeLeft = -1.0)))).any { it.contains("below zero") })
        assertTrue(failureOf(data.copy(foodDays = data.foodDays + data.foodDays)).any { it.contains("twice") })
        val badMeal = encode(data).replace("\"DINNER\"", "\"BRUNCH\"")
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(badMeal)).errors.any { it.contains("unknown meal") })
    }
}
