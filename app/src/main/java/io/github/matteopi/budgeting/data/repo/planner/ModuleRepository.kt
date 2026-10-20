package io.github.matteopi.budgeting.data.repo.planner

import android.database.sqlite.SQLiteConstraintException
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.data.db.dao.PlannerDao
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The user's modules (docs/PLAN.md §13.5): active first, in their order. */
class ModuleRepository(
    private val plannerDao: PlannerDao,
    private val plannerUpdater: PlannerUpdater,
) {
    fun observeModules(): Flow<List<Module>> = plannerDao.observeModules().map { list -> list.map { it.toModel() } }

    suspend fun getModules(): List<Module> = plannerDao.getModules().map { it.toModel() }

    /**
     * Inserts [module] (id 0, added at the end) or updates it. Returns its id, or null when another module already
     * has that code (codes are unique without case).
     */
    suspend fun save(module: Module): Long? = try {
        val id = if (module.id == 0L) {
            plannerDao.insertModule(module.copy(sortOrder = plannerDao.nextModuleSortOrder()).toEntity())
        } else {
            plannerDao.updateModule(module.toEntity())
            module.id
        }
        plannerUpdater.changed()
        id
    } catch (e: SQLiteConstraintException) {
        null
    }

    /** Deletes [id]; its tasks keep their place in the list with no module. */
    suspend fun delete(id: Long) {
        plannerDao.deleteModule(id)
        plannerUpdater.changed()
    }
}
