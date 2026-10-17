package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One ingredient line of a recipe. `text` is the line as written, `grp` the heading it sits under,
 * `quantity` the amount in the ingredient's base unit. The line keeps its row if the ingredient is
 * deleted (the link is cleared), though ingredients are normally archived instead.
 */
@Entity(
    tableName = "recipe_lines",
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
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("recipe_id"), Index("ingredient_id")],
)
data class RecipeLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    val position: Int,
    val grp: String?,
    val text: String,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long?,
    val quantity: Double?,
    val optional: Boolean,
)
