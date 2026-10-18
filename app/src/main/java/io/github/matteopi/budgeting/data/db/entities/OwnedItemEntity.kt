package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Something the kitchen has (docs/PLAN.md §11.11): one row per ingredient. `quantity` is in the
 * ingredient's base unit, null for "some, not measured"; `updated_at` is epoch millis. Deleting the
 * ingredient deletes its row.
 */
@Entity(
    tableName = "owned_items",
    foreignKeys = [
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredient_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OwnedItemEntity(
    @PrimaryKey @ColumnInfo(name = "ingredient_id") val ingredientId: Long,
    val quantity: Double?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
