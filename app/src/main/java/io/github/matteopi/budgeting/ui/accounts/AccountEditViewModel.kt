package io.github.matteopi.budgeting.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.activity.toInputText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditUiState(
    val loading: Boolean = true,
    val accountId: Long? = null,
    val name: String = "",
    val type: AccountType = AccountType.CURRENT,
    /** Only used for a new account: becomes the opening-balance transaction. */
    val balanceText: String = "0.00",
    val overdraftText: String = "0.00",
    val includeInPool: Boolean = true,
    val archived: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
) {
    val isNew: Boolean get() = accountId == null
    val nameValid: Boolean get() = name.isNotBlank()
    val balanceValid: Boolean get() = Money.parse(balanceText) != null
    val overdraftValid: Boolean get() = Money.parse(overdraftText)?.let { !it.isNegative } == true
    val saveEnabled: Boolean get() = nameValid && overdraftValid && (!isNew || balanceValid)
}

/** Add/edit an account (docs/PLAN.md §6.5). */
class AccountEditViewModel(
    private val accountRepository: AccountRepository,
    private val clock: Clock,
    accountId: Long?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountEditUiState(loading = accountId != null, accountId = accountId))
    val uiState: StateFlow<AccountEditUiState> = _uiState.asStateFlow()

    init {
        if (accountId != null) {
            viewModelScope.launch {
                val account = accountRepository.observeAccounts().first().firstOrNull { it.id == accountId }
                _uiState.update { current ->
                    if (account == null) {
                        current.copy(loading = false)
                    } else {
                        current.copy(
                            loading = false,
                            name = account.name,
                            type = account.type,
                            overdraftText = account.overdraftLimit.toInputText(),
                            includeInPool = account.includeInSafeToSpend,
                            archived = account.archived,
                        )
                    }
                }
            }
        }
    }

    fun setName(value: String) = _uiState.update { it.copy(name = value) }
    fun setType(value: AccountType) = _uiState.update { it.copy(type = value) }
    fun setBalanceText(value: String) = _uiState.update { it.copy(balanceText = value) }
    fun setOverdraftText(value: String) = _uiState.update { it.copy(overdraftText = value) }
    fun setIncludeInPool(value: Boolean) = _uiState.update { it.copy(includeInPool = value) }

    fun save() {
        val state = _uiState.value
        if (!state.saveEnabled) return
        val overdraft = Money.parse(state.overdraftText)?.abs() ?: return
        viewModelScope.launch {
            if (state.isNew) {
                val balance = Money.parse(state.balanceText) ?: return@launch
                accountRepository.createAccount(
                    account = Account(
                        name = state.name.trim(),
                        type = state.type,
                        overdraftLimit = overdraft,
                        includeInSafeToSpend = state.includeInPool,
                    ),
                    openingBalance = balance,
                    date = clock.today(),
                )
            } else {
                accountRepository.update(
                    Account(
                        id = requireNotNull(state.accountId),
                        name = state.name.trim(),
                        type = state.type,
                        overdraftLimit = overdraft,
                        includeInSafeToSpend = state.includeInPool,
                        archived = state.archived,
                    ),
                )
            }
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun archive() {
        val id = _uiState.value.accountId ?: return
        viewModelScope.launch {
            accountRepository.archive(id)
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val id = _uiState.value.accountId ?: return
        viewModelScope.launch {
            accountRepository.delete(id)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    companion object {
        fun factory(accountId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                AccountEditViewModel(
                    accountRepository = app.container.accountRepository,
                    clock = app.container.clock,
                    accountId = accountId,
                )
            }
        }
    }
}
