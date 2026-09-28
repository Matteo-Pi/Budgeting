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

    @Query("DELETE FROM reconciliations")
    suspend fun deleteAll()
}
