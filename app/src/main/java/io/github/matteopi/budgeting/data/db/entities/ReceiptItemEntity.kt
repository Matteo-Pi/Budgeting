package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "receipt_items",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptEntity::class,
            parentColumns = ["id"],
            childColumns = ["receipt_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("receipt_id"), Index("product_id"), Index("category_id")],
)
data class ReceiptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "receipt_id") val receiptId: Long,
    @ColumnInfo(name = "product_id") val productId: Long?,
    @ColumnInfo(name = "raw_name") val rawName: String,
    val quantity: Int,
    @ColumnInfo(name = "weight_grams") val weightGrams: Int?,
    @ColumnInfo(name = "unit_price") val unitPrice: Long?,
    val price: Long,
    val discount: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
)
