package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scenario_items",
    foreignKeys = [
        ForeignKey(
            entity = ScenarioEntity::class,
            parentColumns = ["id"],
            childColumns = ["scenario_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scenario_id")],
)
data class ScenarioItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "scenario_id") val scenarioId: Long,
    val type: String,
    val label: String,
    @ColumnInfo(name = "account_id") val accountId: Long?,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    val amount: Long,
    val date: Long?,
    val frequency: String?,
    val interval: Int,
    @ColumnInfo(name = "end_date") val endDate: Long?,
    val percent: Int,
    val regime: String?,
)
