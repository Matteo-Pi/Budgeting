package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.matteopi.budgeting.data.db.entities.ProductAliasEntity
import io.github.matteopi.budgeting.data.db.entities.ProductEntity
import io.github.matteopi.budgeting.data.db.entities.ReceiptEntity
import io.github.matteopi.budgeting.data.db.entities.ReceiptItemEntity
import io.github.matteopi.budgeting.data.db.entities.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** Receipts, their items, products and product aliases (docs/PLAN.md §10.3). */
@Dao
interface ReceiptDao {
    // ---- Receipts ---------------------------------------------------------------------------

    @Query("SELECT * FROM receipts ORDER BY date DESC, created_at DESC, id DESC")
    fun observeReceipts(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun getReceipt(id: Long): ReceiptEntity?

    @Insert
    suspend fun insertReceipt(receipt: ReceiptEntity): Long

    @Insert
    suspend fun insertReceipts(receipts: List<ReceiptEntity>): List<Long>

    @Query("UPDATE receipts SET transaction_id = :transactionId WHERE id = :id")
    suspend fun setTransactionId(id: Long, transactionId: Long?)

    /** Items go with it (foreign key CASCADE). */
    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteReceipt(id: Long)

    @Query("DELETE FROM receipts")
    suspend fun deleteAllReceipts()

    // ---- Items ------------------------------------------------------------------------------

    @Query("SELECT * FROM receipt_items WHERE receipt_id = :receiptId ORDER BY id")
    fun observeItems(receiptId: Long): Flow<List<ReceiptItemEntity>>

    @Query("SELECT * FROM receipt_items ORDER BY receipt_id, id")
    fun observeAllItems(): Flow<List<ReceiptItemEntity>>

    @Insert
    suspend fun insertItems(items: List<ReceiptItemEntity>): List<Long>

    @Query("DELETE FROM receipt_items")
    suspend fun deleteAllItems()

    // ---- Products and aliases ---------------------------------------------------------------

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE, id")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE name = :name COLLATE NOCASE ORDER BY id LIMIT 1")
    suspend fun getProductByName(name: String): ProductEntity?

    @Insert
    suspend fun insertProduct(product: ProductEntity): Long

    @Insert
    suspend fun insertProducts(products: List<ProductEntity>): List<Long>

    @Query("UPDATE products SET category_id = :categoryId WHERE id = :id")
    suspend fun setProductCategory(id: Long, categoryId: Long?)

    /** Aliases go with it (CASCADE); receipt items keep their row with `product_id` cleared (SET_NULL). */
    @Query("DELETE FROM products")
    suspend fun deleteAllProducts()

    @Query("SELECT * FROM product_aliases ORDER BY store, raw_name_key")
    fun observeAliases(): Flow<List<ProductAliasEntity>>

    @Query("SELECT * FROM product_aliases WHERE store = :store AND raw_name_key = :rawNameKey LIMIT 1")
    suspend fun getAlias(store: String, rawNameKey: String): ProductAliasEntity?

    @Insert
    suspend fun insertAlias(alias: ProductAliasEntity): Long

    @Insert
    suspend fun insertAliases(aliases: List<ProductAliasEntity>): List<Long>

    @Query("UPDATE product_aliases SET product_id = :productId WHERE id = :id")
    suspend fun setAliasProduct(id: Long, productId: Long)

    @Query("DELETE FROM product_aliases")
    suspend fun deleteAllAliases()

    // ---- Transactions that belong to a receipt (transactions.receipt_id) --------------------

    @Query("SELECT * FROM transactions WHERE receipt_id = :receiptId ORDER BY id")
    suspend fun transactionsForReceipt(receiptId: Long): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE receipt_id = :receiptId")
    suspend fun deleteTransactionsForReceipt(receiptId: Long)

    @Query("UPDATE transactions SET receipt_id = NULL WHERE receipt_id = :receiptId")
    suspend fun clearReceiptId(receiptId: Long)
}
