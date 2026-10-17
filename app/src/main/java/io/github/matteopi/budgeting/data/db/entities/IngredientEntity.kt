package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An ingredient (docs/PLAN.md §11.3). Quantities and nutrition are REAL, in the ingredient's base
 * unit (`unit` is `G` or `ML`); nutrition is per 100 base units and null means unknown. `price` is
 * the typed pack price in pence and `price_date` an epoch day. The product link is cleared if the
 * product is deleted.
 */
@Entity(
    tableName = "ingredients",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("product_id"),
        Index(value = ["barcode"], unique = true),
    ],
)
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: String,
    @ColumnInfo(name = "pack_size") val packSize: Double,
    @ColumnInfo(name = "pack_label") val packLabel: String,
    val price: Long?,
    @ColumnInfo(name = "price_date") val priceDate: Long?,
    val store: String?,
    @ColumnInfo(name = "product_id") val productId: Long?,
    @ColumnInfo(name = "product_name") val productName: String?,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    val barcode: String?,
    val kcal: Double?,
    val fat: Double?,
    val saturates: Double?,
    val carbs: Double?,
    val sugars: Double?,
    val fibre: Double?,
    val protein: Double?,
    val salt: Double?,
    @ColumnInfo(name = "nutrition_source") val nutritionSource: String,
    @ColumnInfo(name = "veg_kind") val vegKind: String,
    val shelf: String,
    val note: String,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
