package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A named weight of an ingredient, such as `each` = 150 g. `amount` is in the ingredient's base unit. */
@Entity(
    tableName = "ingredient_portions",
    foreignKeys = [
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredient_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["ingredient_id", "name"], unique = true)],
)
data class IngredientPortionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long,
    val name: String,
    val amount: Double,
)
