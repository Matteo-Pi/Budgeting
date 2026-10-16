package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.matteopi.budgeting.data.db.entities.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, created_at DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY date DESC, created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE account_id = :accountId")
    suspend fun getForAccount(accountId: Long): List<TransactionEntity>

    /** Every ordinary transaction (not transfers, opening balances or balance checks), for "change similar". */
    @Query("SELECT * FROM transactions WHERE kind = 'NORMAL'")
    suspend fun getNormal(): List<TransactionEntity>

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM transactions WHERE transfer_group_id = :groupId")
    suspend fun getTransferGroup(groupId: String): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE transfer_group_id = :groupId")
    suspend fun deleteTransferGroup(groupId: String)

    @Query("DELETE FROM transactions WHERE import_batch_id = :batchId")
    suspend fun deleteForBatch(batchId: Long)
}
