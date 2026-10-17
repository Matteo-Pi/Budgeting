package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry on the shopping list: a recipe at some servings, or an extra item (an ingredient with a
 * quantity, or free text). `checked` is used by free-text items only; ingredient lines are ticked
 * through [ShoppingMarkEntity]. Entries go with their recipe or ingredient.
 */
@Entity(
    tableName = "shopping_entries",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredient_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipe_id"), Index("ingredient_id")],
)
data class ShoppingEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long?,
    val servings: Int?,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long?,
    val quantity: Double?,
    @ColumnInfo(name = "free_text") val freeText: String?,
    val checked: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
