package io.github.matteopi.budgeting.ui.planner.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.day.DaySettings
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.insights.StudyInsights
import io.github.matteopi.budgeting.core.planner.insights.WeeklyReview
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.core.planner.suggest.StudyPlanner
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.planner.HabitService
import io.github.matteopi.budgeting.domain.planner.HabitsModel
import io.github.matteopi.budgeting.domain.planner.SuggestionService
import io.github.matteopi.budgeting.domain.planner.SuggestionsModel
import io.github.matteopi.budgeting.ui.planner.components.UndoableEvent
import io.github.matteopi.budgeting.ui.planner.suggest.SuggestionActions
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WeeklyReviewUiState(
    val loading: Boolean = true,
    val today: LocalDate,
    val ui: WeeklyReviewUi? = null,
    val review: WeeklyReview? = null,
    /** False until the planner's first run: the week's suggestions are still being worked out. */
    val suggestionsReady: Boolean = false,
)

/**
 * The weekly review (docs/PLAN.md §12.10, §13.9): how the week went, then the next week's suggestions by day, with
 * *Accept all* and, for each, *Accept*, *Move* (to another time the planner would use) and *Another time*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyReviewViewModel(
    private val dayRepository: DayRepository,
    habitService: HabitService,
    taskRepository: TaskRepository,
    suggestionService: SuggestionService,
    private val actions: SuggestionActions,
    settingsStore: SettingsStore,
    private val clock: Clock,
) : ViewModel() {
    private val events = Channel<UndoableEvent>(Channel.BUFFERED)

    /** What a change did, for the *Undo* snackbar. */
    val eventFlow: Flow<UndoableEvent> = events.receiveAsFlow()

    private val suggestions: StateFlow<SuggestionsModel?> = suggestionService.observe()
        .map<SuggestionsModel, SuggestionsModel?> { it }
        .onStart { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Stored(val records: List<RecordChunk>, val plans: List<PlanBlock>, val categories: List<TimeCategory>)

    private data class Rest(val habits: HabitsModel, val tasks: List<Task>, val studied: Map<Long, Int>, val model: SuggestionsModel?)

    val state: StateFlow<WeeklyReviewUiState> = combine(clock.todayFlow, settingsStore.plannerDay) { today, day -> today to day }
        .flatMapLatest { (today, day) ->
            val zone = clock.zone()
            val (reviewed, _) = WeeklyReview.weeks(today)
            // The reviewed week, and the last week for the deadlines' pace.
            val from = minOf(reviewed.from, today.minusDays(StudyInsights.PACE_DAYS)).minusDays(1)
            val reads = Interval(from.atStartOfDay(zone).toInstant(), maxOf(reviewed.to, today).plusDays(2).atStartOfDay(zone).toInstant())
            val stored = combine(dayRepository.observeRecords(reads), dayRepository.observePlans(reads), dayRepository.observeCategories()) { r, p, c ->
                Stored(r, p, c)
            }
            val rest = combine(habitService.observe(), taskRepository.observeOpenTasks(), dayRepository.observeMinutesByTask(), suggestions) { h, t, s, m ->
                Rest(h, t, s, m)
            }
            combine(stored, rest) { st, r -> build(today, day, st, r) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeeklyReviewUiState(today = clock.today()))

    private fun build(today: LocalDate, day: DaySettings, st: Stored, r: Rest): WeeklyReviewUiState {
        val zone = clock.zone()
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val review = WeeklyReview.build(
            today, now, zone, day, st.records, st.plans, st.categories, r.habits.statuses, r.tasks, r.studied, r.model?.suggestions.orEmpty(),
        )
        return WeeklyReviewUiState(false, today, WeeklyReviewMapping.ui(review, st.categories, today, zone), review, suggestionsReady = r.model != null)
    }

    private fun send(change: suspend () -> UndoableEvent?) {
        viewModelScope.launch { change()?.let { events.send(it) } }
    }

    /** Plans every suggestion of the week shown, in one change. */
    fun acceptAll() {
        val week = state.value.review?.nextWeek?.flatMap { it.second }.orEmpty()
        send { actions.acceptAll(suggestions.value, week) }
    }

    fun accept(key: String) = send { actions.accept(suggestions.value, key, clock.zone()) }

    fun anotherTime(key: String) = send { actions.dismiss(key) }

    /** The other times *Move* offers for the suggestion [key], best first. */
    fun moveOptions(key: String): List<MoveOptionUi> {
        val model = suggestions.value ?: return emptyList()
        val input = model.input ?: return emptyList()
        val suggestion = model.suggestions.firstOrNull { it.key == key } ?: return emptyList()
        return StudyPlanner.alternatives(input, suggestion, model.suggestions).map { WeeklyReviewMapping.moveOption(it, state.value.today, clock.zone()) }
    }

    fun moveTo(key: String, at: Interval) = send { actions.acceptAt(suggestions.value, key, at, clock.zone()) }

    fun undo(event: UndoableEvent) {
        viewModelScope.launch { event.undo() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            WeeklyReviewViewModel(
                it.dayRepository, it.habitService, it.taskRepository, it.suggestionService, SuggestionActions(it.suggestionRepository, it.dayRepository),
                it.settingsStore, it.clock,
            )
        }
    }
}
