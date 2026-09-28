package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.PresetDao
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PresetRepository(
    private val database: BudgetDatabase,
    private val presetDao: PresetDao,
    private val widgetUpdater: WidgetUpdater,
) {
    fun observePresets(): Flow<List<Preset>> = presetDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun add(preset: Preset): Long {
        val id = presetDao.insert(preset.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun update(preset: Preset) {
        presetDao.update(preset.toEntity())
        widgetUpdater.requestUpdate()
    }

    suspend fun delete(id: Long) {
        presetDao.delete(id)
        widgetUpdater.requestUpdate()
    }

    /** Sets sortOrder = index for each id, in order, atomically. */
    suspend fun reorder(orderedIds: List<Long>) {
        database.withTransaction {
            orderedIds.forEachIndexed { index, id -> presetDao.updateSortOrder(id, index) }
        }
        widgetUpdater.requestUpdate()
    }
}
