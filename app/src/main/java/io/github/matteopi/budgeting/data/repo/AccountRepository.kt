package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.reconcile.Reconcile
import io.github.matteopi.budgeting.core.reconcile.ReconcilePlan
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.AccountDao
import io.github.matteopi.budgeting.data.db.dao.CategoryDao
import io.github.matteopi.budgeting.data.db.dao.ReconciliationDao
import io.github.matteopi.budgeting.data.db.dao.TransactionDao
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.widget.WidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Accounts, their live balances, and balance reconciliation ("Update balance"). Also keeps the
 * default-account setting pointing at a live account (see [DefaultAccount]).
 */
class AccountRepository(
    private val database: BudgetDatabase,
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val reconciliationDao: ReconciliationDao,
    private val categoryDao: CategoryDao,
    private val clock: Clock,
    private val widgetUpdater: WidgetUpdater,
    private val settingsStore: SettingsStore,
) {
    fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll().map { list -> list.map { it.toModel() } }

    /** Raw (bank's-view) balance per account id, as of today. */
    fun observeBalances(): Flow<Map<Long, Money>> =
        combine(transactionDao.observeAll(), clock.todayFlow) { entities, today ->
            Balances.balancesByAccount(entities.map { it.toModel() }, today)
        }

    fun observeReconciliations(): Flow<List<Reconciliation>> =
        reconciliationDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeReconciliationsForAccount(accountId: Long): Flow<List<Reconciliation>> =
        reconciliationDao.observeForAccount(accountId).map { list -> list.map { it.toModel() } }

    /** The default account right now (see [DefaultAccount]), or null if there is no account that isn't archived. */
    suspend fun defaultAccountId(): Long? = DefaultAccount.resolveId(allAccounts(), settingsStore.budgetSettings.first().defaultAccountId)

    private suspend fun allAccounts(): List<Account> = accountDao.observeAll().first().map { it.toModel() }

    /**
     * Makes the persisted default-account setting agree with [DefaultAccount]: if it is unset,
     * missing or archived it becomes the earliest-created live account (the new account when it is
     * the first), or unset when none is left. Called after every change that can affect it.
     */
    private suspend fun repairDefaultAccount() {
        val configured = settingsStore.budgetSettings.first().defaultAccountId
        val resolved = DefaultAccount.resolveId(allAccounts(), configured)
        if (resolved != configured) settingsStore.setDefaultAccountId(resolved)
    }

    /** Creates the account and its OPENING_BALANCE transaction atomically. Returns the new account id. */
    suspend fun createAccount(account: Account, openingBalance: Money, date: LocalDate): Long {
        var newId = 0L
        database.withTransaction {
            newId = accountDao.insert(account.copy(id = 0).toEntity())
            transactionDao.insert(
                Transaction(
                    accountId = newId,
                    amount = openingBalance,
                    date = date,
                    createdAtEpochMillis = clock.nowMillis(),
                    categoryId = null,
                    label = "Opening balance",
                    kind = TxKind.OPENING_BALANCE,
                ).toEntity(),
            )
        }
        repairDefaultAccount()
        widgetUpdater.requestUpdate()
        return newId
    }

    suspend fun update(account: Account) {
        accountDao.update(account.toEntity())
        repairDefaultAccount()
        widgetUpdater.requestUpdate()
    }

    /** Archives the account; if it was the default, the default moves to the earliest remaining account. */
    suspend fun archive(id: Long) {
        val entity = accountDao.get(id) ?: return
        accountDao.update(entity.copy(archived = true))
        repairDefaultAccount()
        widgetUpdater.requestUpdate()
    }

    /**
     * Deletes the account; its transactions, rules etc. cascade per the schema's foreign keys. If it
     * was the default, the default moves to the earliest remaining account.
     */
    suspend fun delete(id: Long) {
        accountDao.delete(id)
        repairDefaultAccount()
        widgetUpdater.requestUpdate()
    }

    /**
     * "Update balance": records the gap between what the app thinks the account holds and what
     * the user says the bank shows, as an Untracked transaction (via [Reconcile.plan]).
     */
    suspend fun reconcile(accountId: Long, actualBalance: Money): ReconcilePlan {
        val today = clock.today()
        val transactions = transactionDao.observeAll().first().map { it.toModel() }
        val expected = Balances.accountBalance(accountId, transactions, today)
        val previous = reconciliationDao.latestForAccount(accountId)?.toModel()
        val accountStartDate = transactions
            .filter { it.accountId == accountId && it.kind == TxKind.OPENING_BALANCE }
            .minByOrNull { it.date }?.date
            ?: transactions.filter { it.accountId == accountId }.minByOrNull { it.date }?.date
        val untrackedCategoryId = categoryDao.untrackedCategoryId()
            ?: error("Untracked category is missing; ensureSeeded() must run before reconcile()")
        val plan = Reconcile.plan(
            accountId = accountId,
            expected = expected,
            actual = actualBalance,
            today = today,
            previous = previous,
            accountStartDate = accountStartDate,
            untrackedCategoryId = untrackedCategoryId,
            nowMillis = clock.nowMillis(),
        )
        database.withTransaction {
            val txId = plan.transaction?.let { transactionDao.insert(it.toEntity()) }
            reconciliationDao.insert(plan.reconciliation.copy(transactionId = txId).toEntity())
        }
        widgetUpdater.requestUpdate()
        return plan
    }
}
