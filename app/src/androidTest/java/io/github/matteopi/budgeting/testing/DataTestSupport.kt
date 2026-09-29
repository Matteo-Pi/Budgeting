package io.github.matteopi.budgeting.testing

import androidx.test.core.app.ApplicationProvider
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.SystemKeys
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.domain.ForecastDiagnostics
import io.github.matteopi.budgeting.domain.ForecastState
import java.time.LocalDate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Shared helpers for the data-layer device tests: they run against the app's real container
 * (database, repositories, services). Every test starts from a freshly cleared app, so the database
 * is empty except for the categories that `BudgetApp` seeds asynchronously at start-up.
 *
 * Nothing here is `runBlocking`: the tests wrap their bodies in `runBlocking` (not `runTest`: the
 * forecast is computed on real dispatchers, so virtual time would make every timeout fire at once).
 */
object DataTestSupport {
    /** How long to wait for the forecast, seeding, etc. before giving up. */
    const val WAIT_MS = 30_000L

    const val LLOYDS = "Lloyds current"
    const val SANTANDER_SAVINGS = "Santander Savings"
    const val SANTANDER_CURRENT = "Santander current"

    /** The user's real balances, in pence. */
    val LLOYDS_BALANCE = Money(657_930)
    val SAVINGS_BALANCE = Money(40_000)
    val SANTANDER_BALANCE = Money(50_000)

    /** £6,579.30 + £400.00 + £500.00. */
    val POOL_TOTAL = Money(747_930)

    /** The user's three accounts, as created by [seedThreeAccountBudget]. */
    data class ThreeAccountBudget(
        val lloyds: Account,
        val santanderSavings: Account,
        val santanderCurrent: Account,
    ) {
        val all: List<Account> get() = listOf(lloyds, santanderSavings, santanderCurrent)
    }

    val app: BudgetApp get() = ApplicationProvider.getApplicationContext<BudgetApp>()

    val container: AppContainer get() = app.container

    /** Today, exactly as the app's [io.github.matteopi.budgeting.domain.Clock] sees it. */
    fun today(): LocalDate = container.clock.today()

    // ---- Categories -------------------------------------------------------------------------

    /** The seeded categories; `BudgetApp` inserts them on a background coroutine, so this waits for them. */
    suspend fun awaitCategories(): List<Category> = withTimeout(WAIT_MS) {
        container.categoryRepository.observeCategories().first { it.isNotEmpty() }
    }

    suspend fun category(name: String): Category =
        awaitCategories().firstOrNull { it.name == name }
            ?: throw AssertionError("No category called \"$name\"; have ${awaitCategories().map { it.name }}")

    suspend fun untrackedCategory(): Category =
        awaitCategories().firstOrNull { it.systemKey == SystemKeys.UNTRACKED }
            ?: throw AssertionError("The Untracked system category is missing")

    // ---- Accounts ---------------------------------------------------------------------------

    suspend fun accounts(): List<Account> = container.accountRepository.observeAccounts().first()

    suspend fun account(name: String): Account =
        accounts().firstOrNull { it.name == name }
            ?: throw AssertionError("No account called \"$name\"; have ${accounts().map { it.name }}")

    suspend fun accountById(id: Long): Account =
        accounts().firstOrNull { it.id == id } ?: throw AssertionError("No account with id $id")

    /**
     * Creates an account through the repository (so it gets an opening-balance transaction) and returns it as stored.
     * Waits for the start-up category seeding first: several things a test does next (reconciling, importing,
     * the forecast) need the categories to exist.
     */
    suspend fun createAccount(
        name: String,
        type: AccountType = AccountType.CURRENT,
        overdraft: Money = Money.ZERO,
        opening: Money = Money.ZERO,
        date: LocalDate = today(),
        includeInPool: Boolean = true,
        sortOrder: Int = 0,
    ): Account {
        awaitCategories()
        val id = container.accountRepository.createAccount(
            Account(
                name = name,
                type = type,
                overdraftLimit = overdraft,
                includeInSafeToSpend = includeInPool,
                sortOrder = sortOrder,
            ),
            opening,
            date,
        )
        return accountById(id)
    }

    /**
     * The user's real budget: Lloyds current £6,579.30 with a £1,500 overdraft, Santander Savings
     * £400 and Santander current £500, all in the safe-to-spend pool (total £7,479.30), each opened
     * today. Lloyds is created first, so it becomes the default account.
     */
    suspend fun seedThreeAccountBudget(): ThreeAccountBudget {
        val lloyds = createAccount(
            LLOYDS, AccountType.CURRENT, overdraft = Money.pounds(1_500), opening = LLOYDS_BALANCE, sortOrder = 0,
        )
        val savings = createAccount(SANTANDER_SAVINGS, AccountType.SAVINGS, opening = SAVINGS_BALANCE, sortOrder = 1)
        val santander = createAccount(SANTANDER_CURRENT, AccountType.CURRENT, opening = SANTANDER_BALANCE, sortOrder = 2)
        return ThreeAccountBudget(lloyds, savings, santander)
    }

    // ---- Transactions and balances ----------------------------------------------------------

    suspend fun transactions(): List<Transaction> = container.transactionRepository.observeAll().first()

    suspend fun transactionsOf(accountId: Long): List<Transaction> = transactions().filter { it.accountId == accountId }

    suspend fun transactionsOfKind(kind: TxKind): List<Transaction> = transactions().filter { it.kind == kind }

    suspend fun transaction(id: Long): Transaction? = transactions().firstOrNull { it.id == id }

    /** The account's raw balance as of today, worked out from the ledger. */
    suspend fun balance(accountId: Long): Money = Balances.accountBalance(accountId, transactions(), today())

    /** The account's raw balance as of [asOf], worked out from the ledger. */
    suspend fun balanceAsOf(accountId: Long, asOf: LocalDate): Money = Balances.accountBalance(accountId, transactions(), asOf)

    /** Adds a NORMAL transaction (amount already signed: negative = spending) and returns it as stored. */
    suspend fun addNormal(
        accountId: Long,
        categoryId: Long?,
        signedAmount: Money,
        date: LocalDate = today(),
        label: String = "",
        note: String = "",
    ): Transaction {
        val id = container.transactionRepository.add(
            Transaction(
                accountId = accountId,
                amount = signedAmount,
                date = date,
                createdAtEpochMillis = container.clock.nowMillis(),
                categoryId = categoryId,
                label = label,
                note = note,
                kind = TxKind.NORMAL,
            ),
        )
        return transaction(id) ?: throw AssertionError("Transaction $id was not stored")
    }

    // ---- Forecast ---------------------------------------------------------------------------

    /**
     * Waits until `ForecastService.state` is [ForecastState.Ready] and [predicate] holds for it, and
     * returns that snapshot. The service debounces and recomputes after every write, so a test that
     * has just written something waits for the snapshot that reflects it, not just any Ready one.
     */
    suspend fun awaitReady(
        description: String = "a Ready forecast",
        timeoutMs: Long = WAIT_MS,
        predicate: (ForecastState.Ready) -> Boolean = { true },
    ): ForecastState.Ready {
        val service = container.forecastService
        try {
            return withTimeout(timeoutMs) {
                service.state.first { it is ForecastState.Ready && predicate(it) } as ForecastState.Ready
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out after $timeoutMs ms waiting for $description. Latest state: " +
                    ForecastDiagnostics.describe(service.state.value),
            )
        }
    }
}
