package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.SnapshotDao
import io.github.matteopi.budgeting.widget.WidgetUpdater

/** Accuracy-tracking snapshots, written once a day by [io.github.matteopi.budgeting.work.DailyWorker]. */
class SnapshotRepository(
    private val database: BudgetDatabase,
    private val snapshotDao: SnapshotDao,
    private val widgetUpdater: WidgetUpdater,
) {
    suspend fun saveSnapshots(balances: List<BalanceSnapshot>, categories: List<CategoryWeekSnapshot>) {
        database.withTransaction {
            snapshotDao.insertBalanceSnapshots(balances.map { it.toEntity() })
            snapshotDao.insertCategorySnapshots(categories.map { it.toEntity() })
        }
        widgetUpdater.requestUpdate()
    }

    suspend fun allBalanceSnapshots(): List<BalanceSnapshot> = snapshotDao.allBalanceSnapshots().map { it.toModel() }

    suspend fun allCategorySnapshots(): List<CategoryWeekSnapshot> = snapshotDao.allCategorySnapshots().map { it.toModel() }
}
