package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "balance_snapshots",
    indices = [Index(value = ["created_on", "target_date", "tier"], unique = true)],
)
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_on") val createdOn: Long,
    @ColumnInfo(name = "target_date") val targetDate: Long,
    val tier: Int,
    val p10: Long?,
    val p50: Long,
    val p90: Long?,
)
