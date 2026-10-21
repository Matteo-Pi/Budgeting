package io.github.matteopi.budgeting.ui.planner.suggest

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.SuggestionRepository
import io.github.matteopi.budgeting.domain.planner.SuggestionsModel
import io.github.matteopi.budgeting.ui.planner.components.UndoableEvent
import java.time.ZoneId

/**
 * *Accept* (the suggestion becomes a planned block) and *Another time* (it is dismissed, so the next run offers the
 * time after), each with *Undo* (docs/PLAN.md §12.7).
 */
class SuggestionActions(private val suggestionRepository: SuggestionRepository, private val dayRepository: DayRepository) {

    suspend fun accept(model: SuggestionsModel?, key: String, zone: ZoneId): UndoableEvent? {
        val suggestion = model?.suggestions?.firstOrNull { it.key == key } ?: return null
        // The categories are seeded when the Planner is first used; before that, seed them here.
        val study = model.studyCategoryId ?: run {
            dayRepository.ensureCategoriesSeeded()
            dayRepository.getCategories().let { all -> all.firstOrNull { it.systemKey == CategoryKey.STUDY } ?: all.firstOrNull() }?.id
        } ?: return null
        val plan = suggestionRepository.accept(suggestion, study)
        val start = suggestion.interval.start.atZone(zone).toLocalTime()
        return UndoableEvent("Planned: ${suggestion.demand.title} at ${PlannerText.time(start)}") { dayRepository.applyPlans(plan.inverse()) }
    }

    suspend fun dismiss(key: String): UndoableEvent {
        suggestionRepository.dismiss(key)
        return UndoableEvent("Suggesting another time") { suggestionRepository.undismiss(key) }
    }
}
