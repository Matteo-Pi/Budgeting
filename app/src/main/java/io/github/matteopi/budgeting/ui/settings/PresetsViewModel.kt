package io.github.matteopi.budgeting.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.data.repo.PresetRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs [PresetsScreen]: list, add/edit, reorder, delete. See docs/PLAN.md §6.5 "Presets". */
class PresetsViewModel(
    private val presetRepository: PresetRepository,
    categoryRepository: CategoryRepository,
    accountRepository: AccountRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    data class UiState(
        val presets: List<Preset> = emptyList(),
        val categories: List<Category> = emptyList(),
        val accounts: List<Account> = emptyList(),
        /** The name of the account presets without an account of their own are logged against. */
        val defaultAccountName: String? = null,
    )

    val uiState: StateFlow<UiState> = combine(
        presetRepository.observePresets(),
        categoryRepository.observeCategories(),
        accountRepository.observeAccounts(),
        settingsStore.budgetSettings,
    ) { presets, categories, accounts, settings ->
        UiState(
            presets = presets,
            categories = categories.filter { !it.archived },
            accounts = accounts.filter { !it.archived },
            defaultAccountName = DefaultAccount.resolve(accounts, settings.defaultAccountId)?.name,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Inserts (id == 0) or updates a preset; new presets go to the end of the order. */
    fun save(preset: Preset) {
        viewModelScope.launch {
            if (preset.id == 0L) {
                presetRepository.add(preset.copy(sortOrder = uiState.value.presets.size))
            } else {
                presetRepository.update(preset)
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { presetRepository.delete(id) }
    }

    fun moveUp(id: Long) = reorder(id, delta = -1)

    fun moveDown(id: Long) = reorder(id, delta = 1)

    private fun reorder(id: Long, delta: Int) {
        val orderedIds = uiState.value.presets.sortedBy { it.sortOrder }.map { it.id }
        val reordered = reorderMove(orderedIds, id, delta) ?: return
        viewModelScope.launch { presetRepository.reorder(reordered) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PresetsViewModel(
                    presetRepository = container.presetRepository,
                    categoryRepository = container.categoryRepository,
                    accountRepository = container.accountRepository,
                    settingsStore = container.settingsStore,
                )
            }
        }
    }
}
