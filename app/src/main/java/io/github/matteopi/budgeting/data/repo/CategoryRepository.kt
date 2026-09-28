package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.DefaultCategories
import io.github.matteopi.budgeting.data.db.dao.CategoryDao
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val widgetUpdater: WidgetUpdater,
) {
    fun observeCategories(): Flow<List<Category>> = categoryDao.observeAll().map { list -> list.map { it.toModel() } }

    /** Inserts [DefaultCategories.all] on first run, if the table is empty. */
    suspend fun ensureSeeded() {
        if (categoryDao.count() == 0) {
            categoryDao.insertAll(DefaultCategories.all.map { it.toEntity() })
        }
    }

    suspend fun add(category: Category): Long {
        val id = categoryDao.insert(category.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun update(category: Category) {
        categoryDao.update(category.toEntity())
        widgetUpdater.requestUpdate()
    }

    /** Categories are never deleted (they're referenced by transactions), only archived. */
    suspend fun archive(id: Long) {
        val entity = categoryDao.get(id) ?: return
        categoryDao.update(entity.copy(archived = true))
        widgetUpdater.requestUpdate()
    }

    suspend fun untrackedCategoryId(): Long? = categoryDao.untrackedCategoryId()
}
