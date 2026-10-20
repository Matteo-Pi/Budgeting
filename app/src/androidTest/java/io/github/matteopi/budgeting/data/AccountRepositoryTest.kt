package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.DataTestSupport.transactions
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** [io.github.matteopi.budgeting.data.repo.AccountRepository] against the real database and settings store. */
@RunWith(AndroidJUnit4::class)
class AccountRepositoryTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    private suspend fun defaultAccountSetting(): Long? = container.settingsStore.budgetSettings.first().defaultAccountId

    @Test
    fun createAccountWritesAnOpeningBalanceTransaction() = runBlocking<Unit> {
        val date = today.minusDays(3)
        val account = createAccount("Lloyds current", opening = Money(486_215), date = date)

        val ledger = transactions().filter { it.accountId == account.id }
        assertEquals(1, ledger.size, "an account starts with exactly one transaction")
        val opening = ledger.single()
        assertEquals(TxKind.OPENING_BALANCE, opening.kind)
        assertEquals(Money(486_215), opening.amount)
        assertEquals(date, opening.date)
        assertNull(opening.categoryId, "an opening balance has no category")
        assertNull(opening.transferGroupId)
        assertEquals(Money(486_215), DataTestSupport.balance(account.id))
    }

    @Test
    fun negativeOpeningBalanceIsStoredAsIs() = runBlocking<Unit> {
        val card = createAccount("Credit card", AccountType.CREDIT_CARD, overdraft = Money.pounds(1_000), opening = Money(-12_345))
        assertEquals(Money(-12_345), DataTestSupport.balance(card.id))
        assertEquals(Money(-12_345), transactions().single { it.accountId == card.id }.amount)
    }

    @Test
    fun accountFieldsAreStoredAsGiven() = runBlocking<Unit> {
        val account = createAccount(
            name = "Santander Savings",
            type = AccountType.SAVINGS,
            overdraft = Money.ZERO,
            opening = Money(35_000),
            includeInPool = false,
            sortOrder = 7,
        )
        assertEquals("Santander Savings", account.name)
        assertEquals(AccountType.SAVINGS, account.type)
        assertFalse(account.includeInSafeToSpend)
        assertFalse(account.archived)
        assertEquals(7, account.sortOrder)
        assertTrue(account.id > 0)
    }

    @Test
    fun overdraftLimitIsStoredAndCanBeChanged() = runBlocking<Unit> {
        val lloyds = createAccount("Lloyds current", overdraft = Money.pounds(1_200), opening = Money(486_215))
        assertEquals(Money.pounds(1_200), lloyds.overdraftLimit)
        // The overdraft is a limit, not money: it must not show up in the balance.
        assertEquals(Money(486_215), DataTestSupport.balance(lloyds.id))

        container.accountRepository.update(lloyds.copy(overdraftLimit = Money.pounds(2_000)))
        assertEquals(Money.pounds(2_000), DataTestSupport.accountById(lloyds.id).overdraftLimit)
        assertEquals(Money(486_215), DataTestSupport.balance(lloyds.id))
    }

    @Test
    fun firstAccountBecomesTheSettingsDefault() = runBlocking<Unit> {
        assertNull(defaultAccountSetting(), "no default on a fresh install")
        assertNull(container.accountRepository.defaultAccountId())

        val first = createAccount("Lloyds current", opening = Money(1_000))
        assertEquals(first.id, defaultAccountSetting())
        assertEquals(first.id, container.accountRepository.defaultAccountId())
    }

    @Test
    fun secondAccountDoesNotTakeOverTheDefault() = runBlocking<Unit> {
        val first = createAccount("Lloyds current", opening = Money(1_000))
        val second = createAccount("Santander current", opening = Money(2_000))
        val third = createAccount("Santander Savings", AccountType.SAVINGS, opening = Money(3_000))

        assertEquals(first.id, defaultAccountSetting())
        assertEquals(first.id, container.accountRepository.defaultAccountId())
        assertTrue(second.id > first.id && third.id > second.id, "ids grow in creation order")
    }

    @Test
    fun aDefaultChosenInSettingsSurvivesNewAccounts() = runBlocking<Unit> {
        createAccount("Lloyds current", opening = Money(1_000))
        val second = createAccount("Santander current", opening = Money(2_000))
        container.settingsStore.setDefaultAccountId(second.id)

        createAccount("Santander Savings", AccountType.SAVINGS, opening = Money(3_000))
        assertEquals(second.id, defaultAccountSetting())
        assertEquals(second.id, container.accountRepository.defaultAccountId())
    }

    @Test
    fun archivingTheDefaultMovesItToTheEarliestRemainingAccount() = runBlocking<Unit> {
        val a = createAccount("A", opening = Money(100))
        val b = createAccount("B", opening = Money(200))
        val c = createAccount("C", opening = Money(300))
        assertEquals(a.id, defaultAccountSetting())

        container.accountRepository.archive(a.id)
        assertTrue(DataTestSupport.accountById(a.id).archived)
        assertEquals(b.id, defaultAccountSetting(), "the default moves to the earliest remaining account")
        assertEquals(b.id, container.accountRepository.defaultAccountId())

        container.accountRepository.archive(b.id)
        assertEquals(c.id, defaultAccountSetting())
    }

    @Test
    fun archivingAnAccountThatIsNotTheDefaultLeavesTheDefaultAlone() = runBlocking<Unit> {
        val a = createAccount("A", opening = Money(100))
        val b = createAccount("B", opening = Money(200))
        createAccount("C", opening = Money(300))

        container.accountRepository.archive(b.id)
        assertEquals(a.id, defaultAccountSetting())
    }

    @Test
    fun archivingKeepsTheAccountAndItsBalance() = runBlocking<Unit> {
        val a = createAccount("A", opening = Money(12_345))
        container.accountRepository.archive(a.id)

        assertTrue(DataTestSupport.accountById(a.id).archived)
        assertEquals(Money(12_345), DataTestSupport.balance(a.id))
    }

    @Test
    fun deletingTheDefaultMovesItToTheEarliestRemainingAccount() = runBlocking<Unit> {
        val a = createAccount("A", opening = Money(100))
        val b = createAccount("B", opening = Money(200))
        val c = createAccount("C", opening = Money(300))

        container.accountRepository.delete(a.id)
        assertEquals(listOf(b.id, c.id), DataTestSupport.accounts().map { it.id })
        assertEquals(b.id, defaultAccountSetting())

        container.accountRepository.delete(b.id)
        assertEquals(c.id, defaultAccountSetting())
    }

    @Test
    fun deletingTheLastAccountClearsTheDefault() = runBlocking<Unit> {
        val only = createAccount("Only", opening = Money(100))
        assertEquals(only.id, defaultAccountSetting())

        container.accountRepository.delete(only.id)
        assertTrue(DataTestSupport.accounts().isEmpty())
        assertNull(defaultAccountSetting())
        assertNull(container.accountRepository.defaultAccountId())
    }

    @Test
    fun deletingAnAccountRemovesItsTransactions() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val a = createAccount("A", opening = Money(10_000))
        val b = createAccount("B", opening = Money(20_000))
        DataTestSupport.addNormal(a.id, groceries.id, Money(-500), label = "Tesco")
        DataTestSupport.addNormal(b.id, groceries.id, Money(-700), label = "Aldi")

        container.accountRepository.delete(a.id)

        val remaining = transactions()
        assertTrue(remaining.none { it.accountId == a.id }, "the deleted account's transactions cascade away")
        assertEquals(2, remaining.size, "B keeps its opening balance and its spending")
        assertEquals(Money(19_300), DataTestSupport.balance(b.id))
    }

    @Test
    fun aMissingDefaultIsRepairedTheNextTimeAnAccountChanges() = runBlocking<Unit> {
        val a = createAccount("A", opening = Money(100))
        createAccount("B", opening = Money(200))
        container.settingsStore.setDefaultAccountId(9_999L)

        // Reading resolves to the earliest live account without needing to rewrite the setting...
        assertEquals(a.id, container.accountRepository.defaultAccountId())
        // ...and the next change to an account persists that choice.
        container.accountRepository.update(a.copy(name = "A renamed"))
        assertEquals(a.id, defaultAccountSetting())
    }

    @Test
    fun observeBalancesGivesEachAccountsRawBalance() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val wages = DataTestSupport.category("Wages")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(50_000))
        DataTestSupport.addNormal(current.id, groceries.id, Money(-2_345), today.minusDays(1), "Tesco")
        DataTestSupport.addNormal(current.id, wages.id, Money(20_000), today, "Wages")
        DataTestSupport.addNormal(savings.id, wages.id, Money(1_000), today.minusDays(2), "Interest")

        val balances = container.accountRepository.observeBalances().first()
        assertEquals(Money(117_655), balances[current.id])
        assertEquals(Money(51_000), balances[savings.id])
        assertEquals(2, balances.size)
    }

    @Test
    fun futureDatedTransactionsAreNotInTheBalanceYet() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(10_000))
        DataTestSupport.addNormal(current.id, groceries.id, Money(-4_000), today.plusDays(1), "Tomorrow")

        val balances = container.accountRepository.observeBalances().first()
        assertEquals(Money(10_000), balances[current.id])
        assertEquals(Money(6_000), DataTestSupport.balanceAsOf(current.id, today.plusDays(1)))
    }

    @Test
    fun balancesStayPerAccount() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val a = createAccount("A", opening = Money(1_000))
        val b = createAccount("B", opening = Money(2_000))
        DataTestSupport.addNormal(a.id, groceries.id, Money(-100))

        assertEquals(Money(900), DataTestSupport.balance(a.id))
        assertEquals(Money(2_000), DataTestSupport.balance(b.id))
    }
}
