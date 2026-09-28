package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "import_batches",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("account_id")],
)
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "row_count") val rowCount: Int,
    @ColumnInfo(name = "first_date") val firstDate: Long?,
    @ColumnInfo(name = "last_date") val lastDate: Long?,
)
