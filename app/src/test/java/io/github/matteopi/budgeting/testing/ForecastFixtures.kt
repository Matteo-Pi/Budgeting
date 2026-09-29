package io.github.matteopi.budgeting.testing

import io.github.matteopi.budgeting.core.forecast.ForecastEngine
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SystemKeys
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate

/**
 * Small real-engine fixtures for the app's JVM tests: three accounts (two in the pool, one
 * outside it), opening balances, and the engine's own output, so mapping code is exercised
 * against genuine [ForecastResult]s rather than hand-made ones.
 */
object ForecastFixtures {
    const val CURRENT = 1L
    const val SAVINGS = 2L
    const val CASH = 3L

    val today: LocalDate = LocalDate.of(2026, 9, 28)

    fun accounts(): List<Account> = listOf(
        Account(id = CURRENT, name = "Current", type = AccountType.CURRENT, overdraftLimit = Money.pounds(200)),
        Account(id = SAVINGS, name = "Savings", type = AccountType.SAVINGS, overdraftLimit = Money.pounds(50)),
        Account(id = CASH, name = "Cash", type = AccountType.CASH, includeInSafeToSpend = false),
    )

    fun categories(): List<Category> = listOf(
        Category(id = 1, name = "Groceries", flow = CategoryFlow.OUT, forecastMode = ForecastMode.LEARNED),
        Category(
            id = 99, name = "Untracked", flow = CategoryFlow.BOTH, forecastMode = ForecastMode.LEARNED,
            isSystem = true, systemKey = SystemKeys.UNTRACKED,
        ),
    )

    private fun opening(accountId: Long, pounds: Long, id: Long) = Transaction(
        id = id,
        accountId = accountId,
        amount = Money.pounds(pounds),
        date = today,
        categoryId = null,
        label = "Opening balance",
        kind = TxKind.OPENING_BALANCE,
    )

    fun transactions(
        current: Long = 1_000,
        savings: Long = 500,
        cash: Long = 20,
    ): List<Transaction> = listOf(
        opening(CURRENT, current, id = 1),
        opening(SAVINGS, savings, id = 2),
        opening(CASH, cash, id = 3),
    )

    fun input(
        zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
        accounts: List<Account> = accounts(),
        transactions: List<Transaction> = transactions(),
        monteCarlo: Boolean = true,
        today: LocalDate = this.today,
    ): ForecastInput = ForecastInput(
        today = today,
        accounts = accounts,
        categories = categories(),
        transactions = transactions,
        recurringRules = emptyList(),
        scheduledItems = emptyList(),
        termPeriods = emptyList(),
        predictions = emptyList(),
        sinkingFunds = emptyList(),
        reconciliations = emptyList(),
        settings = BudgetSettings(zeroPoint = zeroPoint, buffer = Money.pounds(100), simulations = 100, forecastHorizonDays = 60),
        runMonteCarlo = monteCarlo,
    )

    fun result(input: ForecastInput = input()): ForecastResult = ForecastEngine.run(input)
}
