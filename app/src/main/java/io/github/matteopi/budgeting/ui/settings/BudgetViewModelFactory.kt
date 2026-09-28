package io.github.matteopi.budgeting.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp

/**
 * A [ViewModelProvider.Factory] for a ViewModel that only needs [AppContainer], read from the
 * [BudgetApp] application instance via `APPLICATION_KEY` (docs/PLAN.md §6.4: "ViewModels get
 * dependencies through `viewModelFactory { initializer { … } }` reading the container from
 * `APPLICATION_KEY`" — implemented here directly against the `ViewModelProvider.Factory`
 * interface rather than the DSL sugar). Used by every ViewModel in the
 * settings/lock/backup/importer packages.
 */
fun <VM : ViewModel> budgetViewModelFactory(create: (AppContainer) -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
            return create(application.container) as T
        }
    }
