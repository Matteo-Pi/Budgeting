package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category_week_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["created_on", "week_start", "category_id", "tier"], unique = true),
        Index("category_id"),
    ],
)
data class CategoryWeekSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_on") val createdOn: Long,
    @ColumnInfo(name = "week_start") val weekStart: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val tier: Int,
    val predicted: Long,
)
