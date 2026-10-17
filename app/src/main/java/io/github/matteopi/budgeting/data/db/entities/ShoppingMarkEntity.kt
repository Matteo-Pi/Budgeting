package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The ticks of one ingredient line of the shopping list, which sums several entries: bought
 * (`checked`) and "I have it" (`have_it`). One row per ingredient.
 */
@Entity(
    tableName = "shopping_marks",
    foreignKeys = [
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredient_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["ingredient_id"], unique = true)],
)
data class ShoppingMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long,
    val checked: Boolean,
    @ColumnInfo(name = "have_it") val haveIt: Boolean,
)
