package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.matteopi.budgeting.data.db.entities.BalanceSnapshotEntity
import io.github.matteopi.budgeting.data.db.entities.CategoryWeekSnapshotEntity

/** Accuracy-tracking snapshots (balance and per-category week-ahead predictions). */
@Dao
interface SnapshotDao {
    @Insert
    suspend fun insertBalanceSnapshots(snapshots: List<BalanceSnapshotEntity>): List<Long>

    @Query("SELECT * FROM balance_snapshots")
    suspend fun allBalanceSnapshots(): List<BalanceSnapshotEntity>

    @Query("DELETE FROM balance_snapshots")
    suspend fun deleteAllBalanceSnapshots()

    @Insert
    suspend fun insertCategorySnapshots(snapshots: List<CategoryWeekSnapshotEntity>): List<Long>

    @Query("SELECT * FROM category_week_snapshots")
    suspend fun allCategorySnapshots(): List<CategoryWeekSnapshotEntity>

    @Query("DELETE FROM category_week_snapshots")
    suspend fun deleteAllCategorySnapshots()
}
