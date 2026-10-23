package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The food diary and the kitchen (docs/PLAN.md §14.3, Room version 10). Dates are epoch days; amounts and
 * nutrients are REAL. A row's nutrients are frozen when it is written.
 */

/**
 * An intake as columns (embedded without a prefix): kcal, grams of the macros, the twelve vitamins and minerals in
 * `Micro`'s units, the veg and pulse grams, and `unknown_mask` (bit n = `IntakeKey` n has an unknown part).
 */
data class NutrientColumns(
    val kcal: Double = 0.0,
    val fat: Double = 0.0,
    val saturates: Double = 0.0,
    val carbs: Double = 0.0,
    val sugars: Double = 0.0,
    @ColumnInfo(name = "added_sugars") val addedSugars: Double = 0.0,
    val fibre: Double = 0.0,
    val protein: Double = 0.0,
    val salt: Double = 0.0,
    @ColumnInfo(name = "vit_a") val vitA: Double = 0.0,
    @ColumnInfo(name = "vit_c") val vitC: Double = 0.0,
    @ColumnInfo(name = "vit_d") val vitD: Double = 0.0,
    @ColumnInfo(name = "vit_e") val vitE: Double = 0.0,
    @ColumnInfo(name = "vit_b6") val vitB6: Double = 0.0,
    @ColumnInfo(name = "vit_b12") val vitB12: Double = 0.0,
    val folate: Double = 0.0,
    val calcium: Double = 0.0,
    val iron: Double = 0.0,
    val potassium: Double = 0.0,
    val magnesium: Double = 0.0,
    val zinc: Double = 0.0,
    @ColumnInfo(name = "veg_grams") val vegGrams: Double = 0.0,
    @ColumnInfo(name = "pulse_grams") val pulseGrams: Double = 0.0,
    @ColumnInfo(name = "unknown_mask") val unknownMask: Int = 0,
)

/**
 * One cooking of a recipe in the kitchen: portions made, and how many are left in the fridge and the freezer. The
 * nutrients and `cost_pence` are for one portion. Deleting the recipe keeps the batch (its link is cleared).
 */
@Entity(
    tableName = "meal_batches",
    foreignKeys = [
        ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipe_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("recipe_id")],
)
data class MealBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long?,
    val name: String,
    @ColumnInfo(name = "made_on") val madeOn: Long,
    val servings: Double,
    val portions: Double,
    @ColumnInfo(name = "fridge_left") val fridgeLeft: Double,
    @ColumnInfo(name = "freezer_left") val freezerLeft: Double,
    @ColumnInfo(name = "frozen_on") val frozenOn: Long?,
    @ColumnInfo(name = "eat_by") val eatBy: Long?,
    val wasted: Double,
    @ColumnInfo(name = "cost_pence") val costPence: Double?,
    val note: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @Embedded val nutrients: NutrientColumns,
)

/**
 * One diary row. `kind` is a `LogKind` name and `meal` a `MealSlot` name; `side_of` is the row this one is a side of
 * (deleting that row deletes its sides). The nutrients and `cost_pence` are for the whole amount.
 */
@Entity(
    tableName = "food_log",
    foreignKeys = [
        ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipe_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = MealBatchEntity::class, parentColumns = ["id"], childColumns = ["batch_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = IngredientEntity::class, parentColumns = ["id"], childColumns = ["ingredient_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = FoodLogEntity::class, parentColumns = ["id"], childColumns = ["side_of"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("date"), Index("recipe_id"), Index("batch_id"), Index("ingredient_id"), Index("side_of")],
)
data class FoodLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val meal: String,
    @ColumnInfo(name = "eaten_at_millis") val eatenAtMillis: Long,
    val kind: String,
    val name: String,
    val amount: Double,
    @ColumnInfo(name = "amount_text") val amountText: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long?,
    @ColumnInfo(name = "batch_id") val batchId: Long?,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long?,
    @ColumnInfo(name = "side_of") val sideOf: Long?,
    @ColumnInfo(name = "cost_pence") val costPence: Double?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @Embedded val nutrients: NutrientColumns,
)

/** A day's water (ml) and note. */
@Entity(tableName = "food_days")
data class FoodDayEntity(
    @PrimaryKey val date: Long,
    @ColumnInfo(name = "water_ml") val waterMl: Int,
    val note: String,
)
