package io.github.matteopi.budgeting.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.ui.food.components.observeFoodBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CompareUiState(
    val loading: Boolean = true,
    /** The recipes in the order of [sort]. */
    val rows: List<CompareRow> = emptyList(),
    val sort: CompareSort = CompareSort(),
    /** For each judged column, the recipes that are best in it. */
    val best: Map<CompareColumn, Set<Long>> = emptyMap(),
    val countOptionalExtras: Boolean = true,
)

/** The Compare tab (docs/PLAN.md §11.8): every recipe at its own servings, sortable by any column. */
class CompareViewModel(private val container: AppContainer) : ViewModel() {
    private val sortFlow = MutableStateFlow(CompareSort())

    val uiState: StateFlow<CompareUiState> = combine(
        container.observeFoodBook(),
        sortFlow,
    ) { book, sort ->
        val rows = CompareMapping.rows(book)
        CompareUiState(
            loading = false,
            rows = CompareMapping.sorted(rows, sort),
            sort = sort,
            best = CompareMapping.bestByColumn(rows),
            countOptionalExtras = book.countOptionalExtras,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareUiState())

    /** A tap on a column heading: sort by it, or turn the order round if it is already the sort. */
    fun sortBy(column: CompareColumn) {
        sortFlow.value = CompareMapping.tapped(sortFlow.value, column)
    }

    /** The *Count optional extras* setting, shared with the recipe screens. */
    fun setCountOptionalExtras(count: Boolean) {
        container.appScope.launch { container.settingsStore.setCountOptionalExtras(count) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                CompareViewModel(app.container)
            }
        }
    }
}
