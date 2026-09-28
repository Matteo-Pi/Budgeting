package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.widget.WidgetUpdater
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransactionRepository(
    private val database: BudgetDatabase,
    private val transactionDao: TransactionDao,
    private val clock: Clock,
    private val widgetUpdater: WidgetUpdater,
) {
    fun observeAll(): Flow<List<Transaction>> = transactionDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeRecent(limit: Int): Flow<List<Transaction>> =
        transactionDao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    suspend fun add(tx: Transaction): Long {
        val id = transactionDao.insert(tx.toEntity())
        widgetUpdater.requestUpdate()
        return id
    }

    /** Inserts both legs of a transfer between two of the user's accounts, sharing a group id. */
    suspend fun addTransfer(fromAccountId: Long, toAccountId: Long, amount: Money, date: LocalDate, label: String) {
        val groupId = UUID.randomUUID().toString()
        val now = clock.nowMillis()
        val magnitude = amount.abs()
        database.withTransaction {
            transactionDao.insert(
                Transaction(
                    accountId = fromAccountId,
                    amount = -magnitude,
                    date = date,
                    createdAtEpochMillis = now,
                    categoryId = null,
                    label = label,
                    kind = TxKind.TRANSFER,
                    transferGroupId = groupId,
                ).toEntity(),
            )
            transactionDao.insert(
                Transaction(
                    accountId = toAccountId,
                    amount = magnitude,
                    date = date,
                    createdAtEpochMillis = now,
                    categoryId = null,
                    label = label,
                    kind = TxKind.TRANSFER,
                    transferGroupId = groupId,
                ).toEntity(),
            )
        }
        widgetUpdater.requestUpdate()
    }

    /** Updates [tx]; if it is one leg of a transfer, mirrors the amount/date onto the other leg. */
    suspend fun update(tx: Transaction) {
        database.withTransaction {
            transactionDao.update(tx.toEntity())
            val groupId = tx.transferGroupId
            if (tx.kind == TxKind.TRANSFER && groupId != null) {
                val other = transactionDao.getTransferGroup(groupId).map { it.toModel() }.firstOrNull { it.id != tx.id }
                if (other != null) {
                    transactionDao.update(other.copy(amount = -tx.amount, date = tx.date).toEntity())
                }
            }
        }
        widgetUpdater.requestUpdate()
    }

    /** Deletes [id]; if it is a transfer leg, both legs are deleted. */
    suspend fun delete(id: Long) {
        database.withTransaction {
            val entity = transactionDao.get(id)
            val groupId = entity?.transferGroupId
            if (entity != null && entity.kind == TxKind.TRANSFER.name && groupId != null) {
                transactionDao.deleteTransferGroup(groupId)
            } else {
                transactionDao.delete(id)
            }
        }
        widgetUpdater.requestUpdate()
    }

    /** Logs a one-tap preset as a NORMAL transaction today. */
    suspend fun logPreset(preset: Preset, defaultAccountId: Long?, today: LocalDate): Long {
        val accountId = preset.accountId ?: defaultAccountId ?: error("No account to log the preset against")
        val signed = if (preset.flow == CategoryFlow.OUT) -preset.amount.abs() else preset.amount.abs()
        val id = transactionDao.insert(
            Transaction(
                accountId = accountId,
                amount = signed,
                date = today,
                createdAtEpochMillis = clock.nowMillis(),
                categoryId = preset.categoryId,
                label = preset.label,
                kind = TxKind.NORMAL,
            ).toEntity(),
        )
        widgetUpdater.requestUpdate()
        return id
    }
}
