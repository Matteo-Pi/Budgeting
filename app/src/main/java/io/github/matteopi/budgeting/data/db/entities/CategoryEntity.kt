package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["system_key"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val flow: String,
    @ColumnInfo(name = "forecast_mode") val forecastMode: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long?,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "is_major_income_default") val isMajorIncomeDefault: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
