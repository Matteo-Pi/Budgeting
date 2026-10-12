package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `transaction_id` is informational (the first transaction created for the receipt) and has no
 * foreign key: the transactions that belong to a receipt are found through
 * `transactions.receipt_id`.
 */
@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val store: String,
    val date: Long,
    val total: Long,
    val source: String,
    @ColumnInfo(name = "transaction_id") val transactionId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
