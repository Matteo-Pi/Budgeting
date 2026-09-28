package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SystemKeys
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate

/** Small, shared fixtures for the forecast engine tests. Ids are hand-assigned and stable. */
object TestData {
    const val CURRENT = 1L
    const val SAVINGS = 2L
    const val OUTSIDE_POOL = 3L // includeInSafeToSpend = false

    const val GROCERIES = 1L
    const val EATING_OUT = 2L
    const val WAGES = 3L
    const val UNTRACKED = 99L
    const val RENT = 10L
    const val STUDENT_LOAN = 11L

    fun accounts(): List<Account> = listOf(
        Account(id = CURRENT, name = "Current", type = AccountType.CURRENT, overdraftLimit = Money.pounds(200)),
        Account(id = SAVINGS, name = "Savings", type = AccountType.SAVINGS),
        Account(id = OUTSIDE_POOL, name = "Outside", type = AccountType.CASH, includeInSafeToSpend = false),
    )

    fun categories(): List<Category> = listOf(
        Category(id = GROCERIES, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED),
        Category(id = EATING_OUT, name = "Eating out", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED),
        Category(id = WAGES, name = "Wages", flow = Flow.IN, forecastMode = ForecastMode.LEARNED),
        Category(
            id = UNTRACKED, name = "Untracked", flow = Flow.BOTH, forecastMode = ForecastMode.LEARNED,
            isSystem = true, systemKey = SystemKeys.UNTRACKED,
        ),
        Category(id = RENT, name = "Rent & bills", flow = Flow.OUT, forecastMode = ForecastMode.SCHEDULED_ONLY),
        Category(
            id = STUDENT_LOAN, name = "Student loan", flow = Flow.IN, forecastMode = ForecastMode.SCHEDULED_ONLY,
            isMajorIncomeDefault = true,
        ),
    )

    fun settings(
        zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
        buffer: Money = Money.ZERO,
        historyStartDate: LocalDate? = null,
        simulations: Int = 200,
        forecastHorizonDays: Int = 182,
    ) = BudgetSettings(
        zeroPoint = zeroPoint,
        buffer = buffer,
        historyStartDate = historyStartDate,
        simulations = simulations,
        forecastHorizonDays = forecastHorizonDays,
    )

    fun input(
        today: LocalDate,
        accounts: List<Account> = accounts(),
        categories: List<Category> = categories(),
        transactions: List<Transaction> = emptyList(),
        recurringRules: List<RecurringRule> = emptyList(),
        scheduledItems: List<ScheduledItem> = emptyList(),
        termPeriods: List<TermPeriod> = emptyList(),
        predictions: List<Prediction> = emptyList(),
        sinkingFunds: List<SinkingFund> = emptyList(),
        reconciliations: List<Reconciliation> = emptyList(),
        settings: BudgetSettings = settings(),
        scenario: Scenario? = null,
        runMonteCarlo: Boolean = false,
        seed: Long = today.toEpochDay(),
    ) = ForecastInput(
        today = today,
        accounts = accounts,
        categories = categories,
        transactions = transactions,
        recurringRules = recurringRules,
        scheduledItems = scheduledItems,
        termPeriods = termPeriods,
        predictions = predictions,
        sinkingFunds = sinkingFunds,
        reconciliations = reconciliations,
        settings = settings,
        scenario = scenario,
        runMonteCarlo = runMonteCarlo,
        seed = seed,
    )

    private var nextTxId = 1L

    fun tx(
        accountId: Long,
        amount: Money,
        date: LocalDate,
        categoryId: Long?,
        kind: TxKind = TxKind.NORMAL,
        label: String = "",
        id: Long = nextTxId++,
    ) = Transaction(id = id, accountId = accountId, amount = amount, date = date, categoryId = categoryId, kind = kind, label = label)
}
