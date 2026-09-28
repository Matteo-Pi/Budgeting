package io.github.matteopi.budgeting.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.matteopi.budgeting.data.db.entities.ImportBatchEntity
import io.github.matteopi.budgeting.data.db.entities.MerchantRuleEntity
import kotlinx.coroutines.flow.Flow

/** Merchant rules and statement-import batches. */
@Dao
interface ImportDao {
    @Query("SELECT * FROM merchant_rules ORDER BY merchant_key")
    fun observeMerchantRules(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules WHERE merchant_key = :key LIMIT 1")
    suspend fun getMerchantRuleByKey(key: String): MerchantRuleEntity?

    @Insert
    suspend fun insertMerchantRule(rule: MerchantRuleEntity): Long

    /** Used by statement import's "remember" step: replaces any existing rule for the same key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMerchantRule(rule: MerchantRuleEntity): Long

    @Insert
    suspend fun insertMerchantRules(rules: List<MerchantRuleEntity>): List<Long>

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun deleteMerchantRule(id: Long)

    @Query("DELETE FROM merchant_rules")
    suspend fun deleteAllMerchantRules()

    @Query("SELECT * FROM import_batches ORDER BY imported_at DESC")
    fun observeBatches(): Flow<List<ImportBatchEntity>>

    @Insert
    suspend fun insertBatch(batch: ImportBatchEntity): Long

    @Insert
    suspend fun insertBatches(batches: List<ImportBatchEntity>): List<Long>

    @Query("DELETE FROM import_batches WHERE id = :id")
    suspend fun deleteBatch(id: Long)

    @Query("DELETE FROM import_batches")
    suspend fun deleteAllBatches()
}
