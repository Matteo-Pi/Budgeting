package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.model.ProductAliasRecord
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.ReceiptDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.data.db.entities.ProductAliasEntity
import io.github.matteopi.budgeting.data.db.entities.ProductEntity
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Receipts, their items and the learned product names (docs/PLAN.md §10.3). Persistence only: the
 * split amounts are worked out by the caller, so this class never changes an account balance
 * unless the caller hands it split transactions that do.
 */
class ReceiptRepository(
    private val database: BudgetDatabase,
    private val receiptDao: ReceiptDao,
    private val transactionDao: TransactionDao,
    private val widgetUpdater: WidgetUpdater,
) {
    fun observeReceipts(): Flow<List<Receipt>> = receiptDao.observeReceipts().map { list -> list.map { it.toModel() } }

    fun observeItems(receiptId: Long): Flow<List<ReceiptItem>> =
        receiptDao.observeItems(receiptId).map { list -> list.map { it.toModel() } }

    fun observeAllItems(): Flow<List<ReceiptItem>> = receiptDao.observeAllItems().map { list -> list.map { it.toModel() } }

    fun observeProducts(): Flow<List<Product>> = receiptDao.observeProducts().map { list -> list.map { it.toModel() } }

    fun observeAliases(): Flow<List<ProductAliasRecord>> =
        receiptDao.observeAliases().map { list -> list.map { it.toModel() } }

    /**
     * Saves [receipt] and [items] in one database transaction. If [replaceTransactionId] is given,
     * that transaction (the plain one the receipt was matched to) is deleted, and each of
     * [splitTransactions] is inserted with `receiptId` set to the new receipt. The receipt's
     * `transactionId` becomes the first split transaction. Any ids on the arguments are ignored
     * (new rows are always created). Returns the new receipt id.
     */
    suspend fun saveReceipt(
        receipt: Receipt,
        items: List<ReceiptItem>,
        splitTransactions: List<Transaction>,
        replaceTransactionId: Long?,
    ): Long {
        val receiptId = database.withTransaction {
            val newId = receiptDao.insertReceipt(receipt.copy(id = 0, transactionId = null).toEntity())
            // A replaced row from a statement import hands its import key to the first split, so
            // importing the same statement again still recognises the payment as already imported.
            val inheritedImportKey = replaceTransactionId?.let { transactionDao.get(it)?.importKey }
            if (replaceTransactionId != null) {
                transactionDao.delete(replaceTransactionId)
            }
            var firstTransactionId: Long? = null
            for ((index, split) in splitTransactions.withIndex()) {
                val importKey = if (index == 0) inheritedImportKey ?: split.importKey else split.importKey
                val txId = transactionDao.insert(split.copy(id = 0, receiptId = newId, importKey = importKey).toEntity())
                if (firstTransactionId == null) firstTransactionId = txId
            }
            if (firstTransactionId != null) {
                receiptDao.setTransactionId(newId, firstTransactionId)
            }
            receiptDao.insertItems(items.map { it.copy(id = 0, receiptId = newId).toEntity() })
            newId
        }
        widgetUpdater.requestUpdate()
        return receiptId
    }

    /**
     * Deletes the receipt and its items. Its split transactions are either merged back into one
     * NORMAL transaction with their summed amount ([mergeBack], keeping the first one's account,
     * date, category and label without the " · Category" suffix), or kept as they are with their
     * `receiptId` cleared.
     */
    suspend fun deleteReceipt(id: Long, mergeBack: Boolean) {
        database.withTransaction {
            val splits = receiptDao.transactionsForReceipt(id).map { it.toModel() }
            if (mergeBack && splits.isNotEmpty()) {
                val first = splits.first()
                val total = splits.fold(Money.ZERO) { sum, tx -> sum + tx.amount }
                receiptDao.deleteTransactionsForReceipt(id)
                transactionDao.insert(
                    Transaction(
                        accountId = first.accountId,
                        amount = total,
                        date = first.date,
                        createdAtEpochMillis = first.createdAtEpochMillis,
                        categoryId = first.categoryId,
                        label = first.label.substringBeforeLast(CATEGORY_SEPARATOR),
                        note = first.note,
                        kind = TxKind.NORMAL,
                    ).toEntity(),
                )
            } else {
                receiptDao.clearReceiptId(id)
            }
            receiptDao.deleteReceipt(id)
        }
        widgetUpdater.requestUpdate()
    }

    /**
     * Remembers that [rawNameKey] at [store] means the product called [name] (matched ignoring
     * case; created if new) so later receipts fill it in. A non-null [categoryId] becomes the
     * product's category. Returns the product id.
     */
    suspend fun learnProduct(store: String, rawNameKey: String, name: String, categoryId: Long?): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "A product needs a name" }
        val productId = database.withTransaction {
            val existing = receiptDao.getProductByName(cleanName)
            val id = if (existing == null) {
                receiptDao.insertProduct(ProductEntity(name = cleanName, categoryId = categoryId))
            } else {
                if (categoryId != null && categoryId != existing.categoryId) {
                    receiptDao.setProductCategory(existing.id, categoryId)
                }
                existing.id
            }
            val alias = receiptDao.getAlias(store, rawNameKey)
            if (alias == null) {
                receiptDao.insertAlias(ProductAliasEntity(store = store, rawNameKey = rawNameKey, productId = id))
            } else if (alias.productId != id) {
                receiptDao.setAliasProduct(alias.id, id)
            }
            id
        }
        widgetUpdater.requestUpdate()
        return productId
    }

    private companion object {
        /** Between the store and the category in a split transaction's label, e.g. "Tesco · Groceries". */
        const val CATEGORY_SEPARATOR = " · "
    }
}
