package io.github.matteopi.budgeting.ui.planner.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.HabitRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.planner.settings.MODULE_COLOURS
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HabitEditUiState(
    val loading: Boolean = true,
    val form: HabitForm,
    val categories: List<TimeCategory> = emptyList(),
    val today: LocalDate,
    /** Set once saved: the habit's id. */
    val savedId: Long? = null,
)

/** Adds or edits a habit (docs/PLAN.md §12.9). */
class HabitEditViewModel(
    private val habitId: Long?,
    private val habitRepository: HabitRepository,
    dayRepository: DayRepository,
    clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(HabitEditUiState(form = HabitForm.new(clock.today(), MODULE_COLOURS[2]), today = clock.today()))
    val state: StateFlow<HabitEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dayRepository.observeCategories().collect { categories -> _state.update { it.copy(categories = categories.filter { c -> !c.archived }) } }
        }
        viewModelScope.launch {
            val existing = habitId?.let { habitRepository.getHabit(it) }
            val count = habitRepository.getHabits().size
            val form = existing?.let(HabitForm::of) ?: HabitForm.new(_state.value.today, MODULE_COLOURS[(count + 2) % MODULE_COLOURS.size])
            _state.update { it.copy(loading = false, form = form) }
        }
    }

    fun update(change: (HabitForm) -> HabitForm) = _state.update { it.copy(form = change(it.form)) }

    fun save() {
        val current = _state.value
        if (current.loading || current.form.errors.isNotEmpty()) return
        viewModelScope.launch {
            val id = habitRepository.save(current.form.toHabit())
            _state.update { it.copy(savedId = id) }
        }
    }

    companion object {
        fun factory(habitId: Long?): ViewModelProvider.Factory = budgetViewModelFactory {
            HabitEditViewModel(habitId, it.habitRepository, it.dayRepository, it.clock)
        }
    }
}
