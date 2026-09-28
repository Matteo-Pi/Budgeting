package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.ScenarioDao
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ScenarioRepository(
    private val database: BudgetDatabase,
    private val scenarioDao: ScenarioDao,
    private val clock: Clock,
    private val widgetUpdater: WidgetUpdater,
) {
    fun observeScenarios(): Flow<List<Scenario>> =
        combine(scenarioDao.observeScenarios(), scenarioDao.observeScenarioItems()) { scenarios, items ->
            val grouped = items.groupBy { it.scenarioId }
            scenarios.map { s -> s.toModel().copy(items = (grouped[s.id] ?: emptyList()).map { it.toModel() }) }
        }

    suspend fun addScenario(scenario: Scenario): Long {
        var id = 0L
        database.withTransaction {
            id = scenarioDao.insertScenario(scenario.toEntity(createdAt = clock.nowMillis()))
            scenarioDao.insertItems(scenario.items.map { it.copy(id = 0, scenarioId = id).toEntity() })
        }
        widgetUpdater.requestUpdate()
        return id
    }

    suspend fun updateScenario(scenario: Scenario) {
        database.withTransaction {
            val existing = scenarioDao.get(scenario.id)
            val createdAt = existing?.createdAt ?: clock.nowMillis()
            scenarioDao.updateScenario(scenario.toEntity(createdAt = createdAt))
            scenarioDao.deleteItemsForScenario(scenario.id)
            scenarioDao.insertItems(scenario.items.map { it.copy(id = 0, scenarioId = scenario.id).toEntity() })
        }
        widgetUpdater.requestUpdate()
    }

    suspend fun deleteScenario(id: Long) {
        scenarioDao.deleteScenario(id)
        widgetUpdater.requestUpdate()
    }
}
