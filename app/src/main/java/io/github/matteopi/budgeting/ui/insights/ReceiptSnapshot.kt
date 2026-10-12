package io.github.matteopi.budgeting.ui.insights

import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.data.repo.ReceiptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Every receipt, item and product at one moment: the input of [ItemInsights]. */
data class ReceiptSnapshot(
    val receipts: List<Receipt>,
    val items: List<ReceiptItem>,
    val products: List<Product>,
)

/** Receipts, their items and the products, emitting again whenever any of them changes. */
fun ReceiptRepository.observeSnapshot(): Flow<ReceiptSnapshot> =
    combine(observeReceipts(), observeAllItems(), observeProducts()) { receipts, items, products ->
        ReceiptSnapshot(receipts, items, products)
    }
