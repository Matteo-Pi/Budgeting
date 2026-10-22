package io.github.matteopi.budgeting.data.repo.planner

import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.core.planner.model.DismissedSuggestion
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.PlanOrigin
import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import io.github.matteopi.budgeting.core.planner.suggest.DemandKind
import io.github.matteopi.budgeting.core.planner.suggest.Suggestion
import io.github.matteopi.budgeting.data.db.dao.StudyDao
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Suggestions taken up or turned down, and exams' revision topics (docs/PLAN.md §13.5). Accepting makes a plan block;
 * dismissing keeps the suggestion's key for [KEEP_DISMISSED], so that time isn't offered again.
 */
class SuggestionRepository(
    private val studyDao: StudyDao,
    private val dayRepository: DayRepository,
    private val clock: Clock,
    private val plannerUpdater: PlannerUpdater,
) {
    companion object {
        val KEEP_DISMISSED: Duration = Duration.ofDays(30)
    }

    // ---- Suggestions --------------------------------------------------------------------------

    /**
     * [suggestion] as a plan block, in [studyCategoryId] unless its demand has a category of its own (a habit's).
     * Returns the plan as applied, for Undo.
     */
    suspend fun accept(suggestion: Suggestion, studyCategoryId: Long): EditPlan<PlanBlock> = acceptAll(listOf(suggestion), studyCategoryId)

    /** The weekly review's *Accept all*: every one of [suggestions] as a plan block, in one change (one Undo). */
    suspend fun acceptAll(suggestions: List<Suggestion>, studyCategoryId: Long): EditPlan<PlanBlock> =
        if (suggestions.isEmpty()) EditPlan() else dayRepository.applyPlans(EditPlan(inserts = suggestions.map { blockOf(it, studyCategoryId) }))

    private fun blockOf(suggestion: Suggestion, studyCategoryId: Long): PlanBlock {
        val d = suggestion.demand
        return PlanBlock(
            start = suggestion.interval.start,
            end = suggestion.interval.end,
            categoryId = d.categoryId ?: studyCategoryId,
            moduleId = d.moduleId,
            taskId = d.taskId,
            habitId = d.habitId,
            topicId = d.topicId,
            origin = if (d.kind == DemandKind.HABIT) PlanOrigin.HABIT else PlanOrigin.SUGGESTION,
            note = d.title,
        )
    }

    fun observeDismissed(): Flow<Set<String>> = studyDao.observeDismissed().map { list -> list.map { it.key }.toSet() }

    suspend fun dismiss(key: String) {
        studyDao.dismiss(DismissedSuggestion(key, Instant.ofEpochMilli(clock.nowMillis())).toEntity())
        plannerUpdater.changed()
    }

    suspend fun undismiss(key: String) {
        studyDao.pruneDismissedKey(key)
        plannerUpdater.changed()
    }

    /** Forgets dismissals older than [KEEP_DISMISSED] (the daily worker). */
    suspend fun prune() {
        studyDao.pruneDismissed(Instant.ofEpochMilli(clock.nowMillis()).minus(KEEP_DISMISSED).toEpochMilli())
    }

    // ---- Revision topics ----------------------------------------------------------------------

    fun observeTopics(): Flow<List<RevisionTopic>> = studyDao.observeTopics().map { list -> list.map { it.toModel() } }

    suspend fun getTopics(): List<RevisionTopic> = studyDao.getTopics().map { it.toModel() }

    /** Adds [topic] (id 0, placed last of its exam's) or saves it. */
    suspend fun saveTopic(topic: RevisionTopic): Long {
        val id = if (topic.id == 0L) {
            val last = getTopics().filter { it.examTaskId == topic.examTaskId }.maxOfOrNull { it.sortOrder } ?: -1
            studyDao.insertTopic(topic.copy(sortOrder = last + 1).toEntity())
        } else {
            studyDao.updateTopic(topic.toEntity())
            topic.id
        }
        plannerUpdater.changed()
        return id
    }

    /** Deletes a topic; its plan blocks stay, no longer naming it (`topic_id` is a plain id). */
    suspend fun deleteTopic(topicId: Long) {
        studyDao.deleteTopic(topicId)
        dayRepository.clearTopic(topicId)
        plannerUpdater.changed()
    }
}
