package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = RecurringRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["recurring_rule_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ScheduledItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduled_item_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_batch_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("account_id"),
        Index("date"),
        Index("category_id"),
        Index("transfer_group_id"),
        Index("recurring_rule_id"),
        Index("scheduled_item_id"),
        Index("import_batch_id"),
        Index(value = ["import_key"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val amount: Long,
    val date: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    val label: String,
    val note: String,
    val kind: String,
    @ColumnInfo(name = "transfer_group_id") val transferGroupId: String?,
    @ColumnInfo(name = "recurring_rule_id") val recurringRuleId: Long?,
    @ColumnInfo(name = "scheduled_item_id") val scheduledItemId: Long?,
    @ColumnInfo(name = "import_batch_id") val importBatchId: Long?,
    @ColumnInfo(name = "import_key") val importKey: String?,
)
