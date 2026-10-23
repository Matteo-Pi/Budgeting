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
 * product is deleted. Version 4 (§11.11) adds `added_sugars` (per 100, null = unknown) and
 * `food_group` (a `FoodGroup` name; null = guessed from the name, `NONE` = neither). Version 10 (§14.3) adds the
 * twelve vitamins and minerals per 100 base units (null = unknown), in `Micro`'s units.
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
    @ColumnInfo(name = "added_sugars") val addedSugars: Double? = null,
    @ColumnInfo(name = "food_group") val foodGroup: String? = null,
    @ColumnInfo(name = "vit_a") val vitA: Double? = null,
    @ColumnInfo(name = "vit_c") val vitC: Double? = null,
    @ColumnInfo(name = "vit_d") val vitD: Double? = null,
    @ColumnInfo(name = "vit_e") val vitE: Double? = null,
    @ColumnInfo(name = "vit_b6") val vitB6: Double? = null,
    @ColumnInfo(name = "vit_b12") val vitB12: Double? = null,
    val folate: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val potassium: Double? = null,
    val magnesium: Double? = null,
    val zinc: Double? = null,
)
