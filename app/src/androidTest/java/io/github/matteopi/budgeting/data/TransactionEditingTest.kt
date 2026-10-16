package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.importer.CategorySuggester
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
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

/**
 * The editing operations of [io.github.matteopi.budgeting.data.repo.TransactionRepository] that the edit sheet
 * relies on (docs/PLAN.md §6.5, "Editing"), against the real database: turning a normal transaction into a move
 * between accounts, re-pointing or removing a transfer's legs, "Not a transfer", and changing similar
 * transactions with a remembered merchant rule.
 */
@RunWith(AndroidJUnit4::class)
class TransactionEditingTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    private suspend fun transferLegs(): List<Transaction> = transactions().filter { it.kind == TxKind.TRANSFER }

    // ---- normal -> transfer -------------------------------------------------------------------------

    @Test
    fun aNormalTransactionBecomesATransferWithTheOppositeLegOnAnotherAccount() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(20_000))
        val spend = DataTestSupport.addNormal(current.id, groceries.id, Money(-2_500), today.minusDays(1), "To savings")
        val totalBefore = DataTestSupport.balance(current.id) + DataTestSupport.balance(savings.id)

        val groupId = container.transactionRepository.convertToTransfer(spend, otherAccountId = savings.id)

        val legs = transferLegs()
        assertEquals(2, legs.size)
        val own = legs.single { it.accountId == current.id }
        val opposite = legs.single { it.accountId == savings.id }
        assertEquals(spend.id, own.id, "the original row is the leg that stays, not a new one")
        assertEquals(TxKind.TRANSFER, own.kind)
        assertNull(own.categoryId, "a transfer has no category")
        assertEquals(groupId, own.transferGroupId)
        assertEquals(groupId, opposite.transferGroupId)
        assertEquals(Money(-2_500), own.amount)
        assertEquals(Money(2_500), opposite.amount, "the opposite leg is negated")
        assertEquals(today.minusDays(1), opposite.date)
        assertEquals("To savings", opposite.label)
        assertNull(opposite.categoryId)
        assertNull(opposite.importKey)

        // The money that left Current now arrives in Savings, so it is no longer spent: the total across both rises by it.
        assertEquals(Money(97_500), DataTestSupport.balance(current.id))
        assertEquals(Money(22_500), DataTestSupport.balance(savings.id))
        assertEquals(totalBefore + Money(2_500), DataTestSupport.balance(current.id) + DataTestSupport.balance(savings.id))
    }

    @Test
    fun aNormalTransactionBecomesALoneTransferLegWhenTheOtherAccountIsNotInTheApp() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(20_000))
        val spend = DataTestSupport.addNormal(current.id, groceries.id, Money(-2_500), label = "To Mum")

        val groupId = container.transactionRepository.convertToTransfer(spend, otherAccountId = null)

        val leg = transferLegs().single()
        assertEquals(spend.id, leg.id)
        assertEquals(current.id, leg.accountId)
        assertEquals(Money(-2_500), leg.amount)
        assertEquals(groupId, leg.transferGroupId)
        assertNull(leg.categoryId)
        assertEquals(Money(97_500), DataTestSupport.balance(current.id), "the balance is unchanged by the conversion")
        assertEquals(Money(20_000), DataTestSupport.balance(savings.id), "no other account was touched")
    }

    @Test
    fun theEditsMadeInTheSheetAreSavedWithTheConversion() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val spend = DataTestSupport.addNormal(current.id, groceries.id, Money(-2_500), label = "Old label", note = "n")

        val edited = spend.copy(amount = Money(-4_000), date = today.minusDays(3), label = "Rent share", categoryId = null)
        container.transactionRepository.convertToTransfer(edited, savings.id)

        val legs = transferLegs()
        assertEquals(setOf(Money(-4_000), Money(4_000)), legs.map { it.amount }.toSet())
        assertTrue(legs.all { it.date == today.minusDays(3) && it.label == "Rent share" })
        assertEquals("n", legs.single { it.accountId == current.id }.note)
        assertEquals(Money(96_000), DataTestSupport.balance(current.id))
        assertEquals(Money(4_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun anIncomeBecomesTheIncomingLegAndTheOtherAccountGetsTheOutgoingOne() = runBlocking<Unit> {
        val family = DataTestSupport.category("Family")
        val current = createAccount("Current", opening = Money(10_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(50_000))
        val gift = DataTestSupport.addNormal(current.id, family.id, Money(3_000), label = "From savings")

        container.transactionRepository.convertToTransfer(gift, savings.id)

        val legs = transferLegs()
        assertEquals(Money(3_000), legs.single { it.accountId == current.id }.amount)
        assertEquals(Money(-3_000), legs.single { it.accountId == savings.id }.amount)
        assertEquals(Money(47_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun onlyANormalTransactionCanBecomeATransfer() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(10_000))
        val opening = transactions().single { it.accountId == current.id }

        val failure = runCatching { container.transactionRepository.convertToTransfer(opening, otherAccountId = null) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "expected an IllegalArgumentException but got $failure")
        assertEquals(TxKind.OPENING_BALANCE, DataTestSupport.transaction(opening.id)?.kind)
    }

    // ---- editing a transfer's legs -----------------------------------------------------------------

    private suspend fun transferBetween(fromName: String, toName: String, pence: Long = 10_000, label: String = "Save"): List<Transaction> {
        container.transactionRepository.addTransfer(
            DataTestSupport.account(fromName).id,
            DataTestSupport.account(toName).id,
            Money(pence),
            today,
            label,
        )
        return transferLegs()
    }

    @Test
    fun theOtherLegMovesToADifferentAccount() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val cash = createAccount("Cash", AccountType.CASH, opening = Money(0))
        val out = transferBetween("Current", "Savings").single { it.accountId == current.id }

        container.transactionRepository.updateTransfer(out.id, current.id, cash.id, Money(10_000), today, "Save")

        val legs = transferLegs()
        assertEquals(2, legs.size)
        assertEquals(cash.id, legs.single { it.amount.isPositive }.accountId)
        assertEquals(current.id, legs.single { it.amount.isNegative }.accountId)
        assertEquals(out.transferGroupId, legs.map { it.transferGroupId }.distinct().single(), "still one transfer")
        assertEquals(Money(0), DataTestSupport.balance(savings.id))
        assertEquals(Money(10_000), DataTestSupport.balance(cash.id))
        assertEquals(Money(90_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun amountDateAndLabelAreChangedOnBothLegs() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val into = transferBetween("Current", "Savings").single { it.accountId == savings.id }

        container.transactionRepository.updateTransfer(into.id, current.id, savings.id, Money(-6_000), today.minusDays(2), "Rent share")

        val legs = transferLegs()
        assertEquals(Money(-6_000), legs.single { it.accountId == current.id }.amount)
        assertEquals(Money(6_000), legs.single { it.accountId == savings.id }.amount)
        assertTrue(legs.all { it.date == today.minusDays(2) && it.label == "Rent share" })
    }

    @Test
    fun notInTheAppRemovesTheOtherLegAndLeavesALoneOne() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(5_000))
        val out = transferBetween("Current", "Savings").single { it.accountId == current.id }

        container.transactionRepository.updateTransfer(out.id, current.id, null, Money(10_000), today, "Save")

        val lone = transferLegs().single()
        assertEquals(current.id, lone.accountId)
        assertEquals(out.id, lone.id)
        assertEquals(Money(5_000), DataTestSupport.balance(savings.id), "Savings no longer has the money")
        assertEquals(Money(90_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun aLoneLegGainsItsMissingSide() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val spend = DataTestSupport.addNormal(current.id, groceries.id, Money(-2_500), label = "Move")
        container.transactionRepository.convertToTransfer(spend, otherAccountId = null)
        val lone = transferLegs().single()

        container.transactionRepository.updateTransfer(lone.id, current.id, savings.id, Money(2_500), lone.date, "Move")

        val legs = transferLegs()
        assertEquals(2, legs.size)
        assertEquals(lone.transferGroupId, legs.map { it.transferGroupId }.distinct().single())
        assertEquals(Money(-2_500), legs.single { it.accountId == current.id }.amount)
        assertEquals(Money(2_500), legs.single { it.accountId == savings.id }.amount)
        assertEquals(Money(2_500), DataTestSupport.balance(savings.id))
    }

    @Test
    fun aLoneIncomingLegCanBeGivenAnOutgoingSide() = runBlocking<Unit> {
        val family = DataTestSupport.category("Family")
        val current = createAccount("Current", opening = Money(0))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(10_000))
        val gift = DataTestSupport.addNormal(current.id, family.id, Money(3_000), label = "From savings")
        container.transactionRepository.convertToTransfer(gift, otherAccountId = null)

        container.transactionRepository.updateTransfer(gift.id, savings.id, current.id, Money(3_000), gift.date, "From savings")

        assertEquals(Money(-3_000), transferLegs().single { it.accountId == savings.id }.amount)
        assertEquals(Money(7_000), DataTestSupport.balance(savings.id))
        assertEquals(Money(3_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun theDirectionOfATransferCanBeSwapped() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val out = transferBetween("Current", "Savings").single { it.accountId == current.id }

        container.transactionRepository.updateTransfer(out.id, savings.id, current.id, Money(10_000), today, "Save")

        assertEquals(Money(110_000), DataTestSupport.balance(current.id))
        assertEquals(Money(-10_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun aTransferNeedsAnAccountInTheAppAndTwoDifferentOnes() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val out = transferBetween("Current", "Savings").single { it.accountId == current.id }

        val bothMissing = runCatching {
            container.transactionRepository.updateTransfer(out.id, null, null, Money(1_000), today, "x")
        }.exceptionOrNull()
        val sameAccount = runCatching {
            container.transactionRepository.updateTransfer(out.id, current.id, current.id, Money(1_000), today, "x")
        }.exceptionOrNull()

        assertTrue(bothMissing is IllegalArgumentException, "expected an IllegalArgumentException but got $bothMissing")
        assertTrue(sameAccount is IllegalArgumentException, "expected an IllegalArgumentException but got $sameAccount")
        assertEquals(setOf(Money(-10_000), Money(10_000)), transferLegs().map { it.amount }.toSet(), "nothing changed")
    }

    // ---- not a transfer ----------------------------------------------------------------------------------

    @Test
    fun notATransferKeepsTheChosenSideAsANormalTransactionAndDeletesTheOther() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val out = transferBetween("Current", "Savings", pence = 2_500, label = "Shop").single { it.accountId == current.id }

        container.transactionRepository.convertToNormal(out.id, groceries.id)

        assertTrue(transferLegs().isEmpty())
        val kept = assertNotNull(DataTestSupport.transaction(out.id))
        assertEquals(TxKind.NORMAL, kept.kind)
        assertEquals(groceries.id, kept.categoryId)
        assertNull(kept.transferGroupId)
        assertEquals(Money(-2_500), kept.amount)
        assertEquals("Shop", kept.label)
        assertEquals(current.id, kept.accountId)
        assertEquals(1, transactions().count { it.label == "Shop" }, "the other leg is gone")
        assertEquals(Money(97_500), DataTestSupport.balance(current.id))
        assertEquals(Money(0), DataTestSupport.balance(savings.id), "the money no longer arrives in Savings")
    }

    @Test
    fun notATransferCanKeepTheReceivingSide() = runBlocking<Unit> {
        val family = DataTestSupport.category("Family")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val into = transferBetween("Current", "Savings", pence = 2_500).single { it.accountId == savings.id }

        container.transactionRepository.convertToNormal(into.id, family.id)

        val kept = assertNotNull(DataTestSupport.transaction(into.id))
        assertEquals(TxKind.NORMAL, kept.kind)
        assertEquals(Money(2_500), kept.amount)
        assertEquals(savings.id, kept.accountId)
        assertEquals(Money(100_000), DataTestSupport.balance(current.id), "Current gets its money back")
        assertEquals(Money(2_500), DataTestSupport.balance(savings.id))
    }

    @Test
    fun notATransferWorksOnALoneLeg() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        val spend = DataTestSupport.addNormal(current.id, groceries.id, Money(-2_500), label = "To Mum")
        container.transactionRepository.convertToTransfer(spend, otherAccountId = null)
        val other = DataTestSupport.category("Other")

        container.transactionRepository.convertToNormal(spend.id, other.id)

        val kept = assertNotNull(DataTestSupport.transaction(spend.id))
        assertEquals(TxKind.NORMAL, kept.kind)
        assertEquals(other.id, kept.categoryId)
        assertNull(kept.transferGroupId)
        assertEquals(Money(97_500), DataTestSupport.balance(current.id))
    }

    @Test
    fun notATransferLeavesOtherTransfersAlone() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(100_000))
        createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val first = transferBetween("Current", "Savings", pence = 1_000, label = "One").single { it.label == "One" && it.accountId == current.id }
        transferBetween("Current", "Savings", pence = 2_000, label = "Two")

        container.transactionRepository.convertToNormal(first.id, groceries.id)

        val remaining = transferLegs()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.label == "Two" })
    }

    @Test
    fun onlyATransferLegCanStopBeingATransfer() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(10_000))
        val spend = DataTestSupport.addNormal(current.id, groceries.id, Money(-100), label = "Plain")

        val failure = runCatching { container.transactionRepository.convertToNormal(spend.id, groceries.id) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "expected an IllegalArgumentException but got $failure")
    }

    // ---- change similar ----------------------------------------------------------------------------------

    private val tescoLabels = listOf("TESCO STORES", "Tesco Stores", "CARD PAYMENT TO TESCO STORES 3012 ON 15-09-2026")

    @Test
    fun recategoriseChangesTheOtherTransactionsOfTheSameMerchant() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val current = createAccount("Current", opening = Money(100_000))
        val edited = DataTestSupport.addNormal(current.id, groceries.id, Money(-1_000), label = "TESCO STORES")
        val others = tescoLabels.mapIndexed { index, label ->
            DataTestSupport.addNormal(current.id, shopping.id, Money(-(index + 2) * 100L), today.minusDays(index + 1L), label)
        }
        val boots = DataTestSupport.addNormal(current.id, shopping.id, Money(-500), label = "Boots")
        val key = MerchantKey.normalise("TESCO STORES")

        assertEquals(3, container.transactionRepository.countRecategorisable(key, groceries.id, edited.id))
        val changed = container.transactionRepository.recategorise(key, groceries.id, edited.id)

        assertEquals(3, changed)
        others.forEach { assertEquals(groceries.id, DataTestSupport.transaction(it.id)?.categoryId, it.label) }
        assertEquals(shopping.id, DataTestSupport.transaction(boots.id)?.categoryId, "another merchant is left alone")
        assertEquals(groceries.id, DataTestSupport.transaction(edited.id)?.categoryId)
        assertEquals(0, container.transactionRepository.countRecategorisable(key, groceries.id, edited.id), "nothing left to change")
        assertTrue(container.importRepository.observeMerchantRules().first().isEmpty(), "no rule unless asked for")
        assertEquals(Money(100_000 - 1_000 - 200 - 300 - 400 - 500), DataTestSupport.balance(current.id), "only categories changed")
    }

    @Test
    fun recategoriseSkipsTheEditedTransactionTransfersReceiptPartsAndTheWrongDirection() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val refunds = DataTestSupport.category("Refunds")
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0))
        val edited = DataTestSupport.addNormal(current.id, shopping.id, Money(-1_000), label = "TESCO STORES")
        val refund = DataTestSupport.addNormal(current.id, refunds.id, Money(500), label = "TESCO STORES")
        val part = container.transactionRepository.add(
            Transaction(
                accountId = current.id,
                amount = Money(-700),
                date = today,
                createdAtEpochMillis = container.clock.nowMillis(),
                categoryId = shopping.id,
                label = "TESCO STORES",
                kind = TxKind.NORMAL,
                receiptId = 99,
            ),
        )
        container.transactionRepository.addTransfer(current.id, savings.id, Money(300), today, "TESCO STORES")
        val key = MerchantKey.normalise("TESCO STORES")

        // Groceries is for spending: the refund (money in), the receipt part and the transfer are not candidates.
        assertEquals(0, container.transactionRepository.countRecategorisable(key, groceries.id, edited.id))
        assertEquals(0, container.transactionRepository.recategorise(key, groceries.id, edited.id))
        assertEquals(refunds.id, DataTestSupport.transaction(refund.id)?.categoryId)
        assertEquals(shopping.id, DataTestSupport.transaction(part)?.categoryId)
        assertEquals(shopping.id, DataTestSupport.transaction(edited.id)?.categoryId, "the edited row is the caller's to change")
        assertTrue(transferLegs().all { it.categoryId == null })
    }

    @Test
    fun recategoriseCanRememberTheMerchantForFutureImports() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val current = createAccount("Current", opening = Money(100_000))
        // The importer would file TESCO under Groceries on its own; the user's choice is Shopping.
        val edited = DataTestSupport.addNormal(current.id, shopping.id, Money(-1_000), label = "TESCO STORES")
        DataTestSupport.addNormal(current.id, groceries.id, Money(-200), label = "Tesco Stores")
        val key = MerchantKey.normalise("TESCO STORES")

        container.transactionRepository.recategorise(key, shopping.id, edited.id, rememberRule = true)

        val rule = container.importRepository.observeMerchantRules().first().single()
        assertEquals(key, rule.merchantKey)
        assertEquals(shopping.id, rule.categoryId)
        // A later statement's row for the same merchant now gets the user's category, not the built-in guess.
        val categories = DataTestSupport.awaitCategories()
        val laterRow = MerchantKey.normalise("CARD PAYMENT TO TESCO STORES 9999 ON 01-10-2026")
        assertEquals(groceries.id, CategorySuggester.suggest(laterRow, emptyList(), categories), "without the rule it is the keyword guess")
        assertEquals(shopping.id, CategorySuggester.suggest(laterRow, listOf(rule), categories))
    }

    @Test
    fun rememberingAMerchantReplacesItsEarlierRule() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        createAccount("Current", opening = Money(100_000))
        val key = MerchantKey.normalise("TESCO STORES")

        container.transactionRepository.rememberMerchantRule(key, shopping.id)
        container.transactionRepository.rememberMerchantRule(key, groceries.id)

        val rule = container.importRepository.observeMerchantRules().first().single()
        assertEquals(groceries.id, rule.categoryId)
    }

    @Test
    fun aBlankMerchantChangesNothingAndSavesNoRule() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val current = createAccount("Current", opening = Money(100_000))
        val untitled = DataTestSupport.addNormal(current.id, shopping.id, Money(-200), label = "")

        assertEquals(0, container.transactionRepository.countRecategorisable("", groceries.id, 0))
        assertEquals(0, container.transactionRepository.recategorise("", groceries.id, 0, rememberRule = true))
        container.transactionRepository.rememberMerchantRule("", groceries.id)

        assertEquals(shopping.id, DataTestSupport.transaction(untitled.id)?.categoryId)
        assertTrue(container.importRepository.observeMerchantRules().first().isEmpty())
    }
}
