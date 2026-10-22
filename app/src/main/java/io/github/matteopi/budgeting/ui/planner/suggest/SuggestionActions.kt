package io.github.matteopi.budgeting.ui.planner.suggest

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.suggest.StudyPlanner
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
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
        val study = studyCategory(model) ?: return null
        val plan = suggestionRepository.accept(suggestion, study)
        val start = suggestion.interval.start.atZone(zone).toLocalTime()
        return UndoableEvent("Planned: ${suggestion.demand.title} at ${PlannerText.time(start)}") { dayRepository.applyPlans(plan.inverse()) }
    }

    /** *Move*: plans the suggestion [key] at [at] instead (one of [StudyPlanner.alternatives]). */
    suspend fun acceptAt(model: SuggestionsModel?, key: String, at: Interval, zone: ZoneId): UndoableEvent? {
        val suggestion = model?.suggestions?.firstOrNull { it.key == key } ?: return null
        val moved = suggestion.copy(key = "${suggestion.demand.key}@${at.start.toEpochMilli()}", interval = at)
        return accept(model.copy(suggestions = listOf(moved)), moved.key, zone)
    }

    /** *Accept all*: [suggestions] planned in one change, with one Undo. */
    suspend fun acceptAll(model: SuggestionsModel?, suggestions: List<Suggestion>): UndoableEvent? {
        if (model == null || suggestions.isEmpty()) return null
        val study = studyCategory(model) ?: return null
        val plan = suggestionRepository.acceptAll(suggestions, study)
        val n = suggestions.size
        return UndoableEvent(if (n == 1) "Planned 1 block" else "Planned $n blocks") { dayRepository.applyPlans(plan.inverse()) }
    }

    /** The categories are seeded when the Planner is first used; before that, they are seeded here. */
    private suspend fun studyCategory(model: SuggestionsModel): Long? = model.studyCategoryId ?: run {
        dayRepository.ensureCategoriesSeeded()
        dayRepository.getCategories().let { all -> all.firstOrNull { it.systemKey == CategoryKey.STUDY } ?: all.firstOrNull() }?.id
    }

    suspend fun dismiss(key: String): UndoableEvent {
        suggestionRepository.dismiss(key)
        return UndoableEvent("Suggesting another time") { suggestionRepository.undismiss(key) }
    }
}
