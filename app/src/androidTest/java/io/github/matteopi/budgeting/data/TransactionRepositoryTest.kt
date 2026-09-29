package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.DataTestSupport.transactions
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** [io.github.matteopi.budgeting.data.repo.TransactionRepository] against the real database. */
@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    private suspend fun transferLegs(): List<Transaction> = transactions().filter { it.kind == TxKind.TRANSFER }

    // ---- add ----------------------------------------------------------------------------------

    @Test
    fun anExpenseIsStoredNegativeAndLowersTheBalance() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val account = createAccount("Current", opening = Money(100_000))

        val id = container.transactionRepository.add(
            Transaction(
                accountId = account.id,
                amount = Money(-2_345),
                date = today,
                createdAtEpochMillis = container.clock.nowMillis(),
                categoryId = groceries.id,
                label = "Tesco",
                note = "weekly shop",
                kind = TxKind.NORMAL,
            ),
        )

        val stored = assertNotNull(DataTestSupport.transaction(id))
        assertEquals(Money(-2_345), stored.amount)
        assertEquals(account.id, stored.accountId)
        assertEquals(groceries.id, stored.categoryId)
        assertEquals("Tesco", stored.label)
        assertEquals("weekly shop", stored.note)
        assertEquals(today, stored.date)
        assertEquals(TxKind.NORMAL, stored.kind)
        assertNull(stored.transferGroupId)
        assertEquals(Money(97_655), DataTestSupport.balance(account.id))
    }

    @Test
    fun incomeIsStoredPositiveAndRaisesTheBalance() = runBlocking<Unit> {
        val wages = DataTestSupport.category("Wages")
        val account = createAccount("Current", opening = Money(10_000))

        val income = DataTestSupport.addNormal(account.id, wages.id, Money(15_000), label = "Cafe shift")

        assertEquals(Money(15_000), income.amount)
        assertEquals(wages.id, income.categoryId)
        assertEquals(Money(25_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun addReturnsDistinctIdsAndKeepsEveryTransaction() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val account = createAccount("Current", opening = Money(10_000))

        val first = DataTestSupport.addNormal(account.id, groceries.id, Money(-100), label = "one")
        val second = DataTestSupport.addNormal(account.id, groceries.id, Money(-100), label = "two")

        assertTrue(first.id != second.id)
        // Two identical amounts on the same day are two purchases, not one.
        assertEquals(Money(9_800), DataTestSupport.balance(account.id))
        assertEquals(3, transactions().count { it.accountId == account.id })
    }

    @Test
    fun observeRecentReturnsTheNewestFirstAndRespectsTheLimit() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val account = createAccount("Current", opening = Money(10_000), date = today.minusDays(30))
        DataTestSupport.addNormal(account.id, groceries.id, Money(-100), today.minusDays(3), "oldest")
        DataTestSupport.addNormal(account.id, groceries.id, Money(-200), today.minusDays(1), "newest")
        DataTestSupport.addNormal(account.id, groceries.id, Money(-300), today.minusDays(2), "middle")

        val recent = container.transactionRepository.observeRecent(2).first()
        assertEquals(listOf("newest", "middle"), recent.map { it.label })
    }

    // ---- transfers ----------------------------------------------------------------------------

    @Test
    fun aTransferCreatesTwoOppositeLegsSharingAGroupId() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(20_000))

        container.transactionRepository.addTransfer(current.id, savings.id, Money(25_000), today, "Save")

        val legs = transferLegs()
        assertEquals(2, legs.size)
        val out = legs.single { it.accountId == current.id }
        val incoming = legs.single { it.accountId == savings.id }
        assertEquals(Money(-25_000), out.amount)
        assertEquals(Money(25_000), incoming.amount)
        val groupId = out.transferGroupId
        assertNotNull(groupId)
        assertEquals(groupId, incoming.transferGroupId)
        assertNull(out.categoryId)
        assertNull(incoming.categoryId)
        assertEquals(today, out.date)
        assertEquals(today, incoming.date)
        assertEquals("Save", out.label)
        assertEquals("Save", incoming.label)

        assertEquals(Money(75_000), DataTestSupport.balance(current.id))
        assertEquals(Money(45_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun aTransferAmountIsTreatedAsAMagnitude() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(10_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))

        container.transactionRepository.addTransfer(current.id, savings.id, Money(-2_500), today, "Save")

        val legs = transferLegs()
        assertEquals(Money(-2_500), legs.single { it.accountId == current.id }.amount)
        assertEquals(Money(2_500), legs.single { it.accountId == savings.id }.amount)
    }

    @Test
    fun aTransferDoesNotChangeTheTotalAcrossAccounts() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(20_000))
        val before = DataTestSupport.balance(current.id) + DataTestSupport.balance(savings.id)

        container.transactionRepository.addTransfer(current.id, savings.id, Money(33_333), today, "Move")

        val after = DataTestSupport.balance(current.id) + DataTestSupport.balance(savings.id)
        assertEquals(before, after)
    }

    @Test
    fun eachTransferGetsItsOwnGroupId() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))

        container.transactionRepository.addTransfer(current.id, savings.id, Money(1_000), today, "One")
        container.transactionRepository.addTransfer(current.id, savings.id, Money(2_000), today, "Two")

        val groups = transferLegs().groupBy { it.transferGroupId }
        assertEquals(2, groups.size)
        assertTrue(groups.values.all { it.size == 2 })
    }

    @Test
    fun updatingTheSourceLegUpdatesTheDestinationLeg() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        container.transactionRepository.addTransfer(current.id, savings.id, Money(10_000), today, "Save")
        val source = transferLegs().single { it.accountId == current.id }

        val newDate = today.minusDays(2)
        container.transactionRepository.update(source.copy(amount = Money(-6_000), date = newDate))

        val legs = transferLegs()
        val out = legs.single { it.accountId == current.id }
        val incoming = legs.single { it.accountId == savings.id }
        assertEquals(Money(-6_000), out.amount)
        assertEquals(newDate, out.date)
        assertEquals(Money(6_000), incoming.amount, "the other leg mirrors the new amount with the opposite sign")
        assertEquals(newDate, incoming.date)
        assertEquals(Money(94_000), DataTestSupport.balance(current.id))
        assertEquals(Money(6_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun updatingTheDestinationLegUpdatesTheSourceLeg() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        container.transactionRepository.addTransfer(current.id, savings.id, Money(10_000), today, "Save")
        val destination = transferLegs().single { it.accountId == savings.id }

        val newDate = today.minusDays(1)
        container.transactionRepository.update(destination.copy(amount = Money(4_000), date = newDate))

        val legs = transferLegs()
        assertEquals(Money(4_000), legs.single { it.accountId == savings.id }.amount)
        assertEquals(Money(-4_000), legs.single { it.accountId == current.id }.amount)
        assertEquals(newDate, legs.single { it.accountId == current.id }.date)
        assertEquals(newDate, legs.single { it.accountId == savings.id }.date)
    }

    @Test
    fun updatingANormalTransactionLeavesOthersAlone() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        container.transactionRepository.addTransfer(current.id, savings.id, Money(10_000), today, "Save")
        val spend = DataTestSupport.addNormal(current.id, groceries.id, Money(-500), label = "Tesco")

        container.transactionRepository.update(spend.copy(amount = Money(-900), label = "Tesco (edited)"))

        val edited = assertNotNull(DataTestSupport.transaction(spend.id))
        assertEquals(Money(-900), edited.amount)
        assertEquals("Tesco (edited)", edited.label)
        val legs = transferLegs()
        assertEquals(Money(-10_000), legs.single { it.accountId == current.id }.amount)
        assertEquals(Money(10_000), legs.single { it.accountId == savings.id }.amount)
    }

    @Test
    fun deletingATransferLegRemovesBothLegs() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(20_000))
        DataTestSupport.addNormal(current.id, groceries.id, Money(-500), label = "Tesco")
        container.transactionRepository.addTransfer(current.id, savings.id, Money(25_000), today, "Save")
        val destination = transferLegs().single { it.accountId == savings.id }

        container.transactionRepository.delete(destination.id)

        assertTrue(transferLegs().isEmpty(), "deleting one leg deletes the other")
        assertEquals(Money(99_500), DataTestSupport.balance(current.id))
        assertEquals(Money(20_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun deletingTheSourceLegAlsoRemovesBothLegs() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        container.transactionRepository.addTransfer(current.id, savings.id, Money(5_000), today, "Keep")
        container.transactionRepository.addTransfer(current.id, savings.id, Money(7_000), today, "Drop")
        val drop = transferLegs().single { it.accountId == current.id && it.label == "Drop" }

        container.transactionRepository.delete(drop.id)

        val remaining = transferLegs()
        assertEquals(2, remaining.size, "only the other transfer's legs are left")
        assertTrue(remaining.all { it.label == "Keep" })
    }

    @Test
    fun deletingANormalTransactionDeletesOnlyThatOne() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(10_000))
        val keep = DataTestSupport.addNormal(current.id, groceries.id, Money(-100), label = "keep")
        val drop = DataTestSupport.addNormal(current.id, groceries.id, Money(-200), label = "drop")

        container.transactionRepository.delete(drop.id)

        assertNull(DataTestSupport.transaction(drop.id))
        assertNotNull(DataTestSupport.transaction(keep.id))
        assertEquals(Money(9_900), DataTestSupport.balance(current.id))
    }

    // ---- presets ------------------------------------------------------------------------------

    private suspend fun savedPreset(
        label: String,
        pence: Long,
        flow: CategoryFlow,
        categoryName: String,
        accountId: Long?,
    ): Preset {
        val category = DataTestSupport.category(categoryName)
        val id = container.presetRepository.add(
            Preset(label = label, amount = Money(pence), flow = flow, categoryId = category.id, accountId = accountId),
        )
        return container.presetRepository.observePresets().first().single { it.id == id }
    }

    @Test
    fun logPresetUsesThePresetsOwnAccount() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(10_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(10_000))
        val preset = savedPreset("Meal deal", 385, CategoryFlow.OUT, "Eating out", accountId = savings.id)

        val id = container.transactionRepository.logPreset(preset, defaultAccountId = current.id, today = today)

        val logged = assertNotNull(DataTestSupport.transaction(id))
        assertEquals(savings.id, logged.accountId, "the preset's account wins over the default")
        assertEquals(Money(-385), logged.amount)
        assertEquals(preset.categoryId, logged.categoryId)
        assertEquals("Meal deal", logged.label)
        assertEquals(today, logged.date)
        assertEquals(TxKind.NORMAL, logged.kind)
        assertEquals(Money(9_615), DataTestSupport.balance(savings.id))
        assertEquals(Money(10_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun logPresetFallsBackToTheDefaultAccount() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(10_000))
        createAccount("Savings", AccountType.SAVINGS, opening = Money(10_000))
        val preset = savedPreset("Bus", 220, CategoryFlow.OUT, "Transport", accountId = null)
        val defaultId = container.accountRepository.defaultAccountId()
        assertEquals(current.id, defaultId, "the first account is the default")

        val id = container.transactionRepository.logPreset(preset, defaultId, today)

        val logged = assertNotNull(DataTestSupport.transaction(id))
        assertEquals(current.id, logged.accountId)
        assertEquals(Money(-220), logged.amount)
        assertEquals(Money(9_780), DataTestSupport.balance(current.id))
    }

    @Test
    fun logPresetForAnIncomePresetIsPositive() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(10_000))
        val preset = savedPreset("Pocket money", 2_000, CategoryFlow.IN, "Family", accountId = null)

        val id = container.transactionRepository.logPreset(preset, current.id, today)

        assertEquals(Money(2_000), assertNotNull(DataTestSupport.transaction(id)).amount)
        assertEquals(Money(12_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun logPresetAlwaysMakesSpendingNegativeWhateverTheStoredSign() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(10_000))
        val eatingOut = DataTestSupport.category("Eating out")
        val odd = Preset(label = "Odd", amount = Money(-500), flow = CategoryFlow.OUT, categoryId = eatingOut.id)

        val id = container.transactionRepository.logPreset(odd, current.id, today)

        assertEquals(Money(-500), assertNotNull(DataTestSupport.transaction(id)).amount)
    }

    @Test
    fun logPresetWithNoAccountAtAllFails() = runBlocking<Unit> {
        val eatingOut = DataTestSupport.category("Eating out")
        val orphan = Preset(label = "Nothing", amount = Money(100), flow = CategoryFlow.OUT, categoryId = eatingOut.id)

        val failure = runCatching {
            container.transactionRepository.logPreset(orphan, defaultAccountId = null, today = today)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "expected an IllegalStateException but got $failure")
        assertTrue(transactions().isEmpty())
    }
}
