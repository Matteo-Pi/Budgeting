package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.matteopi.budgeting.data.db.entities.ReconciliationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReconciliationDao {
    @Query("SELECT * FROM reconciliations ORDER BY date DESC")
    fun observeAll(): Flow<List<ReconciliationEntity>>

    @Query("SELECT * FROM reconciliations WHERE account_id = :accountId ORDER BY date DESC")
    fun observeForAccount(accountId: Long): Flow<List<ReconciliationEntity>>

    @Query("SELECT * FROM reconciliations WHERE account_id = :accountId ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun latestForAccount(accountId: Long): ReconciliationEntity?

    @Insert
    suspend fun insert(reconciliation: ReconciliationEntity): Long

    @Insert
    suspend fun insertAll(reconciliations: List<ReconciliationEntity>): List<Long>

    /** Removes the reconciliations whose balance-update transaction belongs to an import batch (undoing that import). */
    @Query(
        "DELETE FROM reconciliations WHERE transaction_id IN " +
            "(SELECT id FROM transactions WHERE import_batch_id = :batchId)",
    )
    suspend fun deleteForBatch(batchId: Long)

    @Query("DELETE FROM reconciliations")
    suspend fun deleteAll()
}
