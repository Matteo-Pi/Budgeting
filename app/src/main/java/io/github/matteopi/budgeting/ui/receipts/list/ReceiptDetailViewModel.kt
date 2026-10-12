package io.github.matteopi.budgeting.ui.receipts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.model.Transaction
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One item line on the receipt detail screen. */
data class ReceiptItemRow(
    val id: Long,
    /** The product's name when linked, else the printed line. */
    val name: String,
    /** "2 × £1.50", "0.452 kg @ £1.20/kg", or null for a single plain item. */
    val quantityText: String?,
    val categoryName: String?,
    val price: Money,
    /** Zero or negative. */
    val discount: Money,
)

/** One of the transactions the receipt was split into. */
data class ReceiptSplitRow(
    val id: Long,
    val label: String,
    /** A positive amount: what was spent in this part. */
    val amount: Money,
)

data class ReceiptDetailUiState(
    val loading: Boolean = true,
    /** False once loaded if there is no such receipt (e.g. after it was deleted). */
    val found: Boolean = false,
    val storeName: String = "",
    val date: LocalDate? = null,
    val total: Money = Money.ZERO,
    val accountName: String? = null,
    val items: List<ReceiptItemRow> = emptyList(),
    val splits: List<ReceiptSplitRow> = emptyList(),
    /** True after the receipt was deleted: the screen closes itself. */
    val deleted: Boolean = false,
)

class ReceiptDetailViewModel(private val container: AppContainer, private val receiptId: Long) : ViewModel() {
    private val deletedFlow = MutableStateFlow(false)

    private data class ReceiptData(val receipt: Receipt?, val items: List<ReceiptItem>, val products: List<Product>)

    private data class MoneyData(
        val transactions: List<Transaction>,
        val accounts: List<Account>,
        val categories: List<Category>,
    )

    private val receiptData = combine(
        container.receiptRepository.observeReceipts().map { list -> list.firstOrNull { it.id == receiptId } },
        container.receiptRepository.observeItems(receiptId),
        container.receiptRepository.observeProducts(),
    ) { receipt, items, products -> ReceiptData(receipt, items, products) }

    private val moneyData = combine(
        container.transactionRepository.observeAll(),
        container.accountRepository.observeAccounts(),
        container.categoryRepository.observeCategories(),
    ) { transactions, accounts, categories -> MoneyData(transactions, accounts, categories) }

    val uiState: StateFlow<ReceiptDetailUiState> = combine(receiptData, moneyData, deletedFlow) { data, money, deleted ->
        buildState(data, money, deleted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptDetailUiState())

    private fun buildState(data: ReceiptData, money: MoneyData, deleted: Boolean): ReceiptDetailUiState {
        val receipt = data.receipt ?: return ReceiptDetailUiState(loading = false, found = false, deleted = deleted)
        val productsById = data.products.associateBy { it.id }
        val categoriesById = money.categories.associateBy { it.id }
        val splits = money.transactions.filter { it.receiptId == receipt.id }
        val accountId = splits.firstOrNull()?.accountId
        return ReceiptDetailUiState(
            loading = false,
            found = true,
            storeName = ReceiptMapping.storeName(receipt.store),
            date = receipt.date,
            total = receipt.total,
            accountName = accountId?.let { id -> money.accounts.firstOrNull { it.id == id }?.name },
            items = data.items.map { item ->
                ReceiptItemRow(
                    id = item.id,
                    name = ReceiptMapping.itemName(item, productsById),
                    quantityText = ReceiptMapping.quantityText(item),
                    categoryName = item.categoryId?.let { categoriesById[it]?.name },
                    price = item.price,
                    discount = item.discount,
                )
            },
            splits = splits.sortedBy { it.id }.map { ReceiptSplitRow(it.id, it.label, it.amount.abs()) },
            deleted = deleted,
        )
    }

    /** Deletes the receipt, merging its split transactions back into one or keeping them. */
    fun delete(mergeBack: Boolean) {
        viewModelScope.launch {
            container.receiptRepository.deleteReceipt(receiptId, mergeBack)
            deletedFlow.value = true
        }
    }

    companion object {
        fun factory(receiptId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                ReceiptDetailViewModel(app.container, receiptId)
            }
        }
    }
}
