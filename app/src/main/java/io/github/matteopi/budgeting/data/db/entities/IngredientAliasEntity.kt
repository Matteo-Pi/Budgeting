package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A learned name for an ingredient, like a product alias: `name_key` is
 * `IngredientMatching.nameKey(...)` of the name, and is unique, so one name means one ingredient.
 */
@Entity(
    tableName = "ingredient_aliases",
    foreignKeys = [
        ForeignKey(
            entity = IngredientEntity::class,
            parentColumns = ["id"],
            childColumns = ["ingredient_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["name_key"], unique = true),
        Index("ingredient_id"),
    ],
)
data class IngredientAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long,
)
