package io.github.matteopi.budgeting.ui.planner.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.habits.HabitText
import io.github.matteopi.budgeting.data.repo.planner.HabitRepository
import io.github.matteopi.budgeting.domain.planner.HabitService
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HabitDetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val name: String = "",
    val colorArgb: Long = 0,
    val goal: String = "",
    val progress: String = "",
    val streak: String? = null,
    val month: HabitMonthUi? = null,
    /** The earliest month with days: the habit's start. */
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val rates: List<Pair<String, String>> = emptyList(),
    val weeks: List<String> = emptyList(),
    val details: List<String> = emptyList(),
    val paused: Boolean = false,
    val archived: Boolean = false,
    val today: LocalDate? = null,
)

/** One habit's page (docs/PLAN.md §12.9): its month, rates and streaks, *Pause* and *Archive*. */
class HabitDetailViewModel(
    private val habitId: Long,
    habitService: HabitService,
    private val habitRepository: HabitRepository,
    today: LocalDate,
) : ViewModel() {
    private val shown = MutableStateFlow(YearMonth.from(today))

    val state: StateFlow<HabitDetailUiState> = combine(habitService.observe(), shown) { model, month ->
        val status = model.status(habitId) ?: return@combine HabitDetailUiState(loading = false, missing = true)
        val habit = status.habit
        val thisMonth = YearMonth.from(model.today)
        val first = YearMonth.from(habit.startDate)
        val visible = month.coerceIn(minOf(first, thisMonth), thisMonth)
        HabitDetailUiState(
            loading = false,
            name = habit.name,
            colorArgb = habit.colorArgb,
            goal = HabitText.goal(habit.goal),
            progress = HabitText.progress(status),
            streak = HabitText.streak(status),
            month = HabitsMapping.month(status, visible),
            canGoBack = visible.isAfter(first),
            canGoForward = visible.isBefore(thisMonth),
            rates = HabitsMapping.rates(status),
            weeks = HabitsMapping.recentWeeks(status),
            details = HabitsMapping.details(habit, model.categories, model.today),
            paused = HabitsMapping.pausedNow(habit, model.today),
            archived = habit.archived,
            today = model.today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitDetailUiState())

    fun showMonth(step: Long) {
        shown.value = (state.value.month?.month ?: shown.value).plusMonths(step)
    }

    /** *Pause*: from today until resumed. *Resume*: the pause ends yesterday (or goes, if it began today). */
    fun setPaused(paused: Boolean) {
        val today = state.value.today ?: return
        viewModelScope.launch {
            val habit = habitRepository.getHabit(habitId) ?: return@launch
            val from = habit.pausedFrom
            val next = when {
                paused -> habit.copy(pausedFrom = today, pausedUntil = null)
                from == null || !from.isBefore(today) -> habit.copy(pausedFrom = null, pausedUntil = null)
                else -> habit.copy(pausedUntil = today.minusDays(1))
            }
            habitRepository.save(next)
        }
    }

    fun setArchived(archived: Boolean) {
        viewModelScope.launch { habitRepository.setArchived(habitId, archived) }
    }

    companion object {
        fun factory(habitId: Long): ViewModelProvider.Factory = budgetViewModelFactory {
            HabitDetailViewModel(habitId, it.habitService, it.habitRepository, it.clock.today())
        }
    }
}
