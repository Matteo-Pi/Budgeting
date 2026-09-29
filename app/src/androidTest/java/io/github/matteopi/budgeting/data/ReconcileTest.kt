package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.repo.toEntity
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.DataTestSupport.transactionsOfKind
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** "Update balance": [io.github.matteopi.budgeting.data.repo.AccountRepository.reconcile] against the real database. */
@RunWith(AndroidJUnit4::class)
class ReconcileTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    private suspend fun reconciliationsOf(accountId: Long): List<Reconciliation> =
        container.accountRepository.observeReconciliationsForAccount(accountId).first()

    @Test
    fun aLowerRealBalanceRecordsAnUntrackedReconciliationTransaction() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val untracked = DataTestSupport.untrackedCategory()
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(10))
        DataTestSupport.addNormal(account.id, groceries.id, Money(-2_000), today.minusDays(2), "Tesco")
        // The app thinks £980.00; the bank says £950.35.

        val plan = container.accountRepository.reconcile(account.id, Money(95_035))

        assertEquals(Money(-2_965), plan.difference)
        val recorded = transactionsOfKind(TxKind.RECONCILIATION)
        assertEquals(1, recorded.size)
        val tx = recorded.single()
        assertEquals(account.id, tx.accountId)
        assertEquals(Money(-2_965), tx.amount, "the transaction is exactly the difference")
        assertEquals(untracked.id, tx.categoryId, "the gap goes to the Untracked category")
        assertEquals(today, tx.date)
        assertEquals("Balance update", tx.label)
    }

    @Test
    fun aHigherRealBalanceRecordsAPositiveDifference() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(4))

        val plan = container.accountRepository.reconcile(account.id, Money(51_234))

        assertEquals(Money(1_234), plan.difference)
        val tx = transactionsOfKind(TxKind.RECONCILIATION).single()
        assertEquals(Money(1_234), tx.amount)
        assertEquals(DataTestSupport.untrackedCategory().id, tx.categoryId)
    }

    @Test
    fun theReconciliationRecordHoldsExpectedActualAndTheTransaction() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(10))

        container.accountRepository.reconcile(account.id, Money(97_235))

        val records = reconciliationsOf(account.id)
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(account.id, record.accountId)
        assertEquals(today, record.date)
        assertEquals(Money(100_000), record.expected)
        assertEquals(Money(97_235), record.actual)
        val tx = transactionsOfKind(TxKind.RECONCILIATION).single()
        assertEquals(tx.id, record.transactionId, "the record points at the transaction it created")
        assertEquals(1, container.accountRepository.observeReconciliations().first().size)
    }

    @Test
    fun periodStartIsTheDayAfterTheAccountStarted() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(10))

        container.accountRepository.reconcile(account.id, Money(90_000))

        assertEquals(today.minusDays(9), reconciliationsOf(account.id).single().periodStart)
    }

    @Test
    fun periodStartIsTheDayAfterThePreviousReconciliation() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(20))
        container.database.reconciliationDao().insert(
            Reconciliation(
                accountId = account.id,
                date = today.minusDays(3),
                expected = Money(100_000),
                actual = Money(100_000),
                transactionId = null,
                periodStart = today.minusDays(19),
            ).toEntity(),
        )

        container.accountRepository.reconcile(account.id, Money(99_000))

        val newest = reconciliationsOf(account.id).first { it.date == today }
        assertEquals(today.minusDays(2), newest.periodStart)
    }

    @Test
    fun periodStartIsNeverAfterToday() = runBlocking<Unit> {
        // An account opened today would give "tomorrow"; the plan clamps it to today.
        val account = createAccount("Current", opening = Money(100_000), date = today)

        container.accountRepository.reconcile(account.id, Money(99_000))

        assertEquals(today, reconciliationsOf(account.id).single().periodStart)
    }

    @Test
    fun theSameBalanceCreatesNoTransaction() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(10))
        DataTestSupport.addNormal(account.id, groceries.id, Money(-2_000), today.minusDays(1), "Tesco")
        val ledgerBefore = DataTestSupport.transactions()

        val plan = container.accountRepository.reconcile(account.id, Money(98_000))

        assertTrue(plan.difference.isZero)
        assertNull(plan.transaction)
        assertEquals(ledgerBefore, DataTestSupport.transactions(), "nothing is added to the ledger")
        assertTrue(transactionsOfKind(TxKind.RECONCILIATION).isEmpty())
        assertEquals(Money(98_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun theSameBalanceStillLeavesARecordWithoutATransaction() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(10))

        container.accountRepository.reconcile(account.id, Money(100_000))

        val record = reconciliationsOf(account.id).single()
        assertEquals(Money(100_000), record.expected)
        assertEquals(Money(100_000), record.actual)
        assertNull(record.transactionId)
    }

    @Test
    fun theBalanceEqualsTheRealBalanceAfterwards() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val wages = DataTestSupport.category("Wages")
        val account = createAccount("Current", opening = Money(123_456), date = today.minusDays(30))
        DataTestSupport.addNormal(account.id, groceries.id, Money(-1_875), today.minusDays(9), "Aldi")
        DataTestSupport.addNormal(account.id, wages.id, Money(4_500), today.minusDays(5), "Shift")
        DataTestSupport.addNormal(account.id, groceries.id, Money(-999), today.minusDays(1), "Co-op")

        val real = Money(118_001)
        container.accountRepository.reconcile(account.id, real)

        assertEquals(real, DataTestSupport.balance(account.id))
        assertEquals(real, container.accountRepository.observeBalances().first()[account.id])
    }

    @Test
    fun theBalanceIsTheRealOneEvenWhenTheBankShowsAnOverdraft() = runBlocking<Unit> {
        val account = createAccount("Current", overdraft = Money.pounds(500), opening = Money(10_000), date = today.minusDays(5))

        container.accountRepository.reconcile(account.id, Money(-4_250))

        assertEquals(Money(-4_250), DataTestSupport.balance(account.id))
        assertEquals(Money(-14_250), transactionsOfKind(TxKind.RECONCILIATION).single().amount)
    }

    @Test
    fun reconcilingTwiceTakesTheFirstReconciliationIntoAccount() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(10))

        container.accountRepository.reconcile(account.id, Money(90_000))
        val second = container.accountRepository.reconcile(account.id, Money(95_000))

        assertEquals(Money(5_000), second.difference, "the second gap is measured from the first real balance")
        val amounts = transactionsOfKind(TxKind.RECONCILIATION).map { it.amount }.sortedBy { it.pence }
        assertEquals(listOf(Money(-10_000), Money(5_000)), amounts)
        assertEquals(Money(95_000), DataTestSupport.balance(account.id))
        val records = reconciliationsOf(account.id)
        assertEquals(2, records.size)
        val byActual = records.associateBy { it.actual }
        assertEquals(Money(100_000), byActual.getValue(Money(90_000)).expected)
        assertEquals(Money(90_000), byActual.getValue(Money(95_000)).expected)
        assertTrue(records.all { it.transactionId != null })
    }

    @Test
    fun reconcilingOneAccountLeavesTheOthersAlone() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000), date = today.minusDays(10))
        val savings = createAccount("Savings", opening = Money(50_000), date = today.minusDays(10))

        container.accountRepository.reconcile(current.id, Money(99_000))

        assertEquals(Money(99_000), DataTestSupport.balance(current.id))
        assertEquals(Money(50_000), DataTestSupport.balance(savings.id))
        assertTrue(reconciliationsOf(savings.id).isEmpty())
        assertEquals(current.id, transactionsOfKind(TxKind.RECONCILIATION).single().accountId)
    }

    @Test
    fun theUntrackedCategoryIsTheSystemCategoryForBothDirections() = runBlocking<Unit> {
        val untracked = DataTestSupport.untrackedCategory()
        assertTrue(untracked.isSystem)
        assertEquals("untracked", untracked.systemKey)
        assertEquals(CategoryFlow.BOTH, untracked.flow)
        assertEquals(untracked.id, assertNotNull(container.categoryRepository.untrackedCategoryId()))
    }
}
