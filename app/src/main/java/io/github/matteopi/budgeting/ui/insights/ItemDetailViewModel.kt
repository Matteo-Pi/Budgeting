package io.github.matteopi.budgeting.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.receipts.ItemPrices
import io.github.matteopi.budgeting.core.receipts.PriceChange
import io.github.matteopi.budgeting.core.receipts.PricePoint
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ItemDetailUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val name: String = "",
    val period: InsightsPeriod = InsightsPeriod.WEEKS_12,
    /** Spent on this item in [period]: price less discounts. */
    val spend: Money = Money.ZERO,
    /** Units bought in [period]. */
    val quantity: Int = 0,
    /** Number of times it appeared on a receipt in [period]. */
    val purchases: Int = 0,
    /** Every recorded unit price, oldest first (not limited to [period]). */
    val history: List<PricePoint> = emptyList(),
    /** Latest unit price against the one before it; null with fewer than two purchases. */
    val change: PriceChange? = null,
)

/** Price history and spend of one product (docs/PLAN.md §10.4 "Insights → Items"). */
class ItemDetailViewModel(container: AppContainer, private val productId: Long) : ViewModel() {
    private val periodFlow = MutableStateFlow(InsightsPeriod.WEEKS_12)

    val uiState: StateFlow<ItemDetailUiState> = combine(
        container.receiptRepository.observeSnapshot(),
        periodFlow,
        container.clock.todayFlow,
    ) { data, period, today ->
        val product = data.products.firstOrNull { it.id == productId }
        if (product == null) {
            ItemDetailUiState(loading = false, found = false, period = period)
        } else {
            val purchased = ReceiptMapping.purchasedItems(data.receipts, data.items).filter { it.productId == productId }
            val from = ItemInsights.periodStart(period, today)
            val stats = ItemPrices.mostBought(purchased, limit = 1, from = from, to = today).firstOrNull()
            val history = ItemPrices.history(purchased, productId)
            ItemDetailUiState(
                loading = false,
                found = true,
                name = product.name,
                period = period,
                spend = stats?.spend ?: Money.ZERO,
                quantity = stats?.quantity ?: 0,
                purchases = stats?.purchases ?: 0,
                history = history,
                change = ItemPrices.change(history),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemDetailUiState())

    fun selectPeriod(period: InsightsPeriod) {
        periodFlow.value = period
    }

    companion object {
        fun factory(productId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                ItemDetailViewModel(app.container, productId)
            }
        }
    }
}
