package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.FoodDay
import io.github.matteopi.budgeting.core.nutrition.Intake
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.data.db.entities.FoodDayEntity
import io.github.matteopi.budgeting.data.db.entities.FoodLogEntity
import io.github.matteopi.budgeting.data.db.entities.MealBatchEntity
import io.github.matteopi.budgeting.data.db.entities.NutrientColumns
import java.time.LocalDate

/*
 * Entity <-> core model mapping for the food diary and the kitchen (docs/PLAN.md §14.3), in the style of
 * FoodMappers.kt. An enum name this version doesn't know reads as the default instead of failing.
 */

/** The columns in `IntakeKey` order, then the veg and pulse grams and the unknown mask. */
fun Intake.toColumns(): NutrientColumns {
    val a = amounts
    return NutrientColumns(
        kcal = a[0], fat = a[1], saturates = a[2], carbs = a[3], sugars = a[4], addedSugars = a[5], fibre = a[6],
        protein = a[7], salt = a[8], vitA = a[9], vitC = a[10], vitD = a[11], vitE = a[12], vitB6 = a[13], vitB12 = a[14],
        folate = a[15], calcium = a[16], iron = a[17], potassium = a[18], magnesium = a[19], zinc = a[20],
        vegGrams = vegGrams, pulseGrams = pulseGrams, unknownMask = unknownMask,
    )
}

fun NutrientColumns.toIntake(): Intake = Intake(
    amounts = listOf(
        kcal, fat, saturates, carbs, sugars, addedSugars, fibre, protein, salt,
        vitA, vitC, vitD, vitE, vitB6, vitB12, folate, calcium, iron, potassium, magnesium, zinc,
    ),
    vegGrams = vegGrams,
    pulseGrams = pulseGrams,
    unknown = Intake.unknownOf(unknownMask),
)

fun MealBatchEntity.toModel(): Batch = Batch(
    id = id,
    recipeId = recipeId,
    name = name,
    madeOn = LocalDate.ofEpochDay(madeOn),
    servings = servings,
    portions = portions,
    fridgeLeft = fridgeLeft,
    freezerLeft = freezerLeft,
    frozenOn = frozenOn?.let { LocalDate.ofEpochDay(it) },
    eatBy = eatBy?.let { LocalDate.ofEpochDay(it) },
    wasted = wasted,
    costPence = costPence,
    note = note,
    createdAtEpochMillis = createdAtMillis,
    intake = nutrients.toIntake(),
)

fun Batch.toEntity(): MealBatchEntity = MealBatchEntity(
    id = id,
    recipeId = recipeId,
    name = name,
    madeOn = madeOn.toEpochDay(),
    servings = servings,
    portions = portions,
    fridgeLeft = fridgeLeft,
    freezerLeft = freezerLeft,
    frozenOn = frozenOn?.toEpochDay(),
    eatBy = eatBy?.toEpochDay(),
    wasted = wasted,
    costPence = costPence,
    note = note,
    createdAtMillis = createdAtEpochMillis,
    nutrients = intake.toColumns(),
)

fun FoodLogEntity.toModel(): LogItem = LogItem(
    id = id,
    date = LocalDate.ofEpochDay(date),
    meal = MealSlot.entries.firstOrNull { it.name == meal } ?: MealSlot.SNACKS,
    eatenAtEpochMillis = eatenAtMillis,
    kind = LogKind.entries.firstOrNull { it.name == kind } ?: LogKind.QUICK,
    name = name,
    amount = amount,
    amountText = amountText,
    recipeId = recipeId,
    batchId = batchId,
    ingredientId = ingredientId,
    sideOf = sideOf,
    costPence = costPence,
    createdAtEpochMillis = createdAtMillis,
    intake = nutrients.toIntake(),
)

fun LogItem.toEntity(): FoodLogEntity = FoodLogEntity(
    id = id,
    date = date.toEpochDay(),
    meal = meal.name,
    eatenAtMillis = eatenAtEpochMillis,
    kind = kind.name,
    name = name,
    amount = amount,
    amountText = amountText,
    recipeId = recipeId,
    batchId = batchId,
    ingredientId = ingredientId,
    sideOf = sideOf,
    costPence = costPence,
    createdAtMillis = createdAtEpochMillis,
    nutrients = intake.toColumns(),
)

fun FoodDayEntity.toModel(): FoodDay = FoodDay(LocalDate.ofEpochDay(date), waterMl, note)

fun FoodDay.toEntity(): FoodDayEntity = FoodDayEntity(date.toEpochDay(), waterMl, note)
