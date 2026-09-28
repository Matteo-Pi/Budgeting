package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sinking_funds")
data class SinkingFundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val target: Long,
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "target_date") val targetDate: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    val status: String,
)
