package io.github.matteopi.budgeting.ui.receipts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.data.repo.ReceiptRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row of the receipts list. */
data class ReceiptListRow(
    val id: Long,
    val storeName: String,
    val date: LocalDate,
    val total: Money,
    val itemCount: Int,
)

data class ReceiptsUiState(
    val loading: Boolean = true,
    /** Newest first. */
    val rows: List<ReceiptListRow> = emptyList(),
)

class ReceiptsViewModel(receiptRepository: ReceiptRepository) : ViewModel() {
    val uiState: StateFlow<ReceiptsUiState> = combine(
        receiptRepository.observeReceipts(),
        receiptRepository.observeAllItems(),
    ) { receipts, items ->
        val countByReceipt = items.groupingBy { it.receiptId }.eachCount()
        ReceiptsUiState(
            loading = false,
            rows = receipts.map { receipt ->
                ReceiptListRow(
                    id = receipt.id,
                    storeName = ReceiptMapping.storeName(receipt.store),
                    date = receipt.date,
                    total = receipt.total,
                    itemCount = countByReceipt[receipt.id] ?: 0,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptsUiState())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                ReceiptsViewModel(app.container.receiptRepository)
            }
        }
    }
}
