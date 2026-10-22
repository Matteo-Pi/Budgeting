package io.github.matteopi.budgeting.ui.planner.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.domain.planner.InsightsRange
import io.github.matteopi.budgeting.domain.planner.InsightsService
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PlannerInsightsUiState(
    val loading: Boolean = true,
    val range: InsightsRange = InsightsRange.WEEK,
    /** A date in the period shown. */
    val focus: LocalDate,
    val today: LocalDate,
    val ui: PlannerInsightsUi? = null,
) {
    /** The period shown has days after today's period, so there is no next one yet. */
    val isCurrent: Boolean get() = InsightsService.periodOf(range, focus).to >= today
}

/** The Insights tab (docs/PLAN.md §12.10, §13.9): this week or month, earlier ones a tap back. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerInsightsViewModel(
    insightsService: InsightsService,
    private val clock: Clock,
) : ViewModel() {
    private val range = MutableStateFlow(InsightsRange.WEEK)
    private val focus = MutableStateFlow(clock.today())

    val state: StateFlow<PlannerInsightsUiState> = combine(range, focus) { r, f -> r to f }
        .flatMapLatest { (r, f) ->
            insightsService.observe(r, f).map { model ->
                PlannerInsightsUiState(loading = false, range = r, focus = f, today = model.today, ui = PlannerInsightsMapping.ui(model))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannerInsightsUiState(focus = clock.today(), today = clock.today()))

    fun setRange(value: InsightsRange) {
        range.value = value
    }

    /** One week or month back (-1) or on (1); never past the current one. */
    fun step(by: Int) {
        val next = when (range.value) {
            InsightsRange.WEEK -> focus.value.plusWeeks(by.toLong())
            InsightsRange.MONTH -> focus.value.plusMonths(by.toLong())
        }
        focus.value = minOf(next, clock.today())
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory { PlannerInsightsViewModel(it.insightsService, it.clock) }
    }
}
