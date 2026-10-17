package io.github.matteopi.budgeting.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A recipe (docs/PLAN.md §11.3). Its ingredient lines and its steps, tips, equipment and storage
 * notes are in `recipe_lines` and `recipe_steps`. `tags` is comma-separated; `source` is `MINE`,
 * `CLAUDE` or `IMPORTED`; `created_at` and `updated_at` are epoch milliseconds.
 */
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "short_title") val shortTitle: String?,
    val tagline: String?,
    val style: String?,
    val serves: Int,
    @ColumnInfo(name = "prep_mins") val prepMins: Int?,
    @ColumnInfo(name = "cook_mins") val cookMins: Int?,
    @ColumnInfo(name = "time_note") val timeNote: String?,
    @ColumnInfo(name = "served_with") val servedWith: String?,
    val tags: String,
    val rating: Int,
    val notes: String,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val archived: Boolean,
)
