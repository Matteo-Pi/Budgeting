package io.github.matteopi.budgeting.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.ForecastMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(private val container: AppContainer) : ViewModel() {
    val categories: StateFlow<List<Category>> = container.categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, flow: CategoryFlow, forecastMode: ForecastMode, colorArgb: Long?) {
        viewModelScope.launch {
            container.categoryRepository.add(
                Category(name = name, flow = flow, forecastMode = forecastMode, colorArgb = colorArgb),
            )
        }
    }

    fun update(category: Category) {
        viewModelScope.launch { container.categoryRepository.update(category) }
    }

    fun archive(id: Long) {
        viewModelScope.launch { container.categoryRepository.archive(id) }
    }

    /** There's no repository `unarchive`; archiving is just `update` with `archived = false`. */
    fun unarchive(category: Category) {
        viewModelScope.launch { container.categoryRepository.update(category.copy(archived = false)) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = budgetViewModelFactory { CategoriesViewModel(it) }
    }
}
