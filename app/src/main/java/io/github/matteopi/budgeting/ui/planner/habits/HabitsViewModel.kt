package io.github.matteopi.budgeting.ui.planner.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.domain.planner.HabitService
import io.github.matteopi.budgeting.ui.planner.components.UndoableEvent
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HabitsUiState(
    val loading: Boolean = true,
    val today: LocalDate,
    val sections: HabitsSections = HabitsSections(),
) {
    val isEmpty: Boolean get() = sections.today.isEmpty() && sections.other.isEmpty() && sections.archived.isEmpty()
}

/** The Habits tab (docs/PLAN.md §12.9, §13.9): today's habits with a tick, an amount or *Skip today*, then the rest. */
class HabitsViewModel(
    habitService: HabitService,
    private val actions: HabitActions,
    today: LocalDate,
) : ViewModel() {
    private val events = Channel<UndoableEvent>(Channel.BUFFERED)

    /** What a change did, for the *Undo* snackbar. */
    val eventFlow: Flow<UndoableEvent> = events.receiveAsFlow()

    val state: StateFlow<HabitsUiState> = habitService.observe()
        .map { model -> HabitsUiState(loading = false, today = model.today, sections = HabitsMapping.sections(model)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUiState(today = today))

    private fun send(change: suspend (LocalDate) -> UndoableEvent?) {
        viewModelScope.launch { change(state.value.today)?.let { events.send(it) } }
    }

    fun toggle(habitId: Long) = send { actions.toggle(habitId, it) }

    fun add(habitId: Long, amount: Int) = send { actions.add(habitId, it, amount) }

    fun skip(habitId: Long, skipped: Boolean) = send { actions.skip(habitId, it, skipped) }

    fun undo(event: UndoableEvent) {
        viewModelScope.launch { event.undo() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory {
            HabitsViewModel(it.habitService, HabitActions(it.habitRepository), it.clock.today())
        }
    }
}
