package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** [io.github.matteopi.budgeting.data.repo.ReceiptRepository] against the real database. */
@RunWith(AndroidJUnit4::class)
class ReceiptRepositoryTest {
    private val container get() = DataTestSupport.container
    private val repo get() = container.receiptRepository
    private val today: LocalDate = DataTestSupport.today()

    /** A £42.35 shop at Tesco on a £1,000 account: the plain transaction a receipt gets matched to. */
    private class Shop(val account: Account, val groceriesId: Long, val householdId: Long, val plain: Transaction)

    private suspend fun shop(): Shop {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val account = createAccount("Current", opening = Money.pounds(1_000), date = today.minusDays(10))
        val plain = DataTestSupport.addNormal(
            account.id, groceries.id, Money(-4_235), today.minusDays(1), "Tesco", "weekly shop",
        )
        return Shop(account, groceries.id, shopping.id, plain)
    }

    private fun receiptFor(shop: Shop) = Receipt(
        store = "TESCO",
        date = shop.plain.date,
        total = Money(4_235),
        source = "PHOTO",
        createdAtEpochMillis = container.clock.nowMillis(),
    )

    private fun itemsFor(shop: Shop) = listOf(
        ReceiptItem(
            receiptId = 0, rawName = "TS SEMI SKMD MLK 2PT", quantity = 2, unitPrice = Money(150),
            price = Money(300), discount = Money(-50), categoryId = shop.groceriesId,
        ),
        ReceiptItem(receiptId = 0, rawName = "BANANAS LOOSE", weightGrams = 452, price = Money(100), categoryId = shop.groceriesId),
        ReceiptItem(receiptId = 0, rawName = "USB CABLE", price = Money(1_235), categoryId = shop.householdId),
    )

    /** The shop split into £30.00 groceries and £12.35 shopping: the same total as [Shop.plain]. */
    private fun splitsFor(shop: Shop) = listOf(
        shop.plain.copy(id = 0, amount = Money(-3_000), categoryId = shop.groceriesId, label = "Tesco · Groceries"),
        shop.plain.copy(id = 0, amount = Money(-1_235), categoryId = shop.householdId, label = "Tesco · Shopping"),
    )

    private suspend fun save(shop: Shop): Long = repo.saveReceipt(
        receipt = receiptFor(shop),
        items = itemsFor(shop),
        splitTransactions = splitsFor(shop),
        replaceTransactionId = shop.plain.id,
    )

    private suspend fun splitsOf(receiptId: Long): List<Transaction> =
        DataTestSupport.transactions().filter { it.receiptId == receiptId }.sortedBy { it.id }

    // ---- save ---------------------------------------------------------------------------------

    @Test
    fun savingASplitReceiptReplacesTheMatchedTransactionAndKeepsTheBalance() = runBlocking<Unit> {
        val shop = shop()
        val before = DataTestSupport.balance(shop.account.id)
        assertEquals(Money(95_765), before)

        val receiptId = save(shop)

        assertNull(DataTestSupport.transaction(shop.plain.id), "the plain transaction is gone")
        val splits = splitsOf(receiptId)
        assertEquals(listOf(Money(-3_000), Money(-1_235)), splits.map { it.amount })
        assertEquals(listOf(shop.groceriesId, shop.householdId), splits.map { it.categoryId })
        assertEquals(listOf("Tesco · Groceries", "Tesco · Shopping"), splits.map { it.label })
        assertTrue(splits.all { it.accountId == shop.account.id && it.date == shop.plain.date && it.kind == TxKind.NORMAL })
        assertEquals(before, DataTestSupport.balance(shop.account.id), "the balance is unchanged")
        assertEquals(
            Money(-4_235),
            DataTestSupport.transactions().filter { it.categoryId != null && it.date == shop.plain.date }
                .fold(Money.ZERO) { sum, tx -> sum + tx.amount },
        )
    }

    @Test
    fun savedIdsAreLinkedTogether() = runBlocking<Unit> {
        val shop = shop()

        val receiptId = save(shop)

        assertNotEquals(0L, receiptId)
        val receipt = assertNotNull(repo.observeReceipts().first().singleOrNull())
        assertEquals(receiptId, receipt.id)
        assertEquals("TESCO", receipt.store)
        assertEquals(Money(4_235), receipt.total)
        assertEquals("PHOTO", receipt.source)
        assertEquals(shop.plain.date, receipt.date)
        val splits = splitsOf(receiptId)
        assertEquals(2, splits.size)
        assertEquals(splits.first().id, receipt.transactionId, "the receipt points at the first split")

        val items = repo.observeItems(receiptId).first()
        assertEquals(3, items.size)
        assertTrue(items.all { it.receiptId == receiptId && it.id != 0L })
        assertEquals(Money(-50), items[0].discount)
        assertEquals(2, items[0].quantity)
        assertEquals(452, items[1].weightGrams)
        assertNull(items[1].unitPrice)
        assertEquals(items, repo.observeAllItems().first())
    }

    @Test
    fun savingWithoutAMatchedTransactionJustAddsTheSplits() = runBlocking<Unit> {
        val shop = shop()
        val before = DataTestSupport.balance(shop.account.id)

        val receiptId = repo.saveReceipt(receiptFor(shop), itemsFor(shop), splitsFor(shop), replaceTransactionId = null)

        assertNotNull(DataTestSupport.transaction(shop.plain.id), "nothing was replaced")
        assertEquals(2, splitsOf(receiptId).size)
        assertEquals(before + Money(-4_235), DataTestSupport.balance(shop.account.id))
    }

    @Test
    fun aSingleCategoryReceiptKeepsOneTransactionWithItsReceiptId() = runBlocking<Unit> {
        val shop = shop()
        val before = DataTestSupport.balance(shop.account.id)

        val receiptId = repo.saveReceipt(
            receiptFor(shop),
            itemsFor(shop),
            listOf(shop.plain.copy(id = 0, label = "Tesco")),
            replaceTransactionId = shop.plain.id,
        )

        val splits = splitsOf(receiptId)
        assertEquals(1, splits.size)
        assertEquals(Money(-4_235), splits.single().amount)
        assertEquals(before, DataTestSupport.balance(shop.account.id))
    }

    @Test
    fun editingASplitTransactionKeepsItsReceiptId() = runBlocking<Unit> {
        val shop = shop()
        val receiptId = save(shop)
        val split = splitsOf(receiptId).first()

        container.transactionRepository.update(split.copy(note = "checked"))

        val stored = assertNotNull(DataTestSupport.transaction(split.id))
        assertEquals("checked", stored.note)
        assertEquals(receiptId, stored.receiptId)
    }

    // ---- delete -------------------------------------------------------------------------------

    @Test
    fun deletingWithMergeBackRestoresOneTransactionWithTheTotal() = runBlocking<Unit> {
        val shop = shop()
        val before = DataTestSupport.balance(shop.account.id)
        val receiptId = save(shop)

        repo.deleteReceipt(receiptId, mergeBack = true)

        assertTrue(repo.observeReceipts().first().isEmpty())
        assertTrue(repo.observeAllItems().first().isEmpty(), "the items went with the receipt")
        assertTrue(DataTestSupport.transactions().none { it.receiptId != null })
        val merged = DataTestSupport.transactions().filter { it.label == "Tesco" }
        assertEquals(1, merged.size)
        assertEquals(Money(-4_235), merged.single().amount)
        assertEquals(shop.account.id, merged.single().accountId)
        assertEquals(shop.plain.date, merged.single().date)
        assertEquals(TxKind.NORMAL, merged.single().kind)
        assertEquals(shop.groceriesId, merged.single().categoryId, "the first split's category")
        assertTrue(DataTestSupport.transactions().none { it.label.contains("·") }, "no split transactions remain")
        assertEquals(before, DataTestSupport.balance(shop.account.id))
    }

    @Test
    fun deletingWithoutMergeBackKeepsTheSplitTransactions() = runBlocking<Unit> {
        val shop = shop()
        val before = DataTestSupport.balance(shop.account.id)
        val receiptId = save(shop)

        repo.deleteReceipt(receiptId, mergeBack = false)

        assertTrue(repo.observeReceipts().first().isEmpty())
        assertTrue(repo.observeAllItems().first().isEmpty())
        val kept = DataTestSupport.transactions().filter { it.label.startsWith("Tesco · ") }.sortedBy { it.id }
        assertEquals(listOf(Money(-3_000), Money(-1_235)), kept.map { it.amount })
        assertTrue(kept.all { it.receiptId == null }, "the link to the deleted receipt is cleared")
        assertEquals(before, DataTestSupport.balance(shop.account.id))
    }

    @Test
    fun deletingOneReceiptLeavesAnotherUntouched() = runBlocking<Unit> {
        val shop = shop()
        val first = save(shop)
        val other = DataTestSupport.addNormal(shop.account.id, shop.groceriesId, Money(-1_000), today, "Tesco")
        val second = repo.saveReceipt(
            receiptFor(shop).copy(date = today, total = Money(1_000)),
            listOf(ReceiptItem(receiptId = 0, rawName = "BREAD", price = Money(1_000), categoryId = shop.groceriesId)),
            listOf(other.copy(id = 0)),
            replaceTransactionId = other.id,
        )

        repo.deleteReceipt(first, mergeBack = true)

        assertEquals(listOf(second), repo.observeReceipts().first().map { it.id })
        assertEquals(1, repo.observeAllItems().first().size)
        assertEquals(1, splitsOf(second).size)
    }

    // ---- products -----------------------------------------------------------------------------

    @Test
    fun learningAProductCreatesItAndItsAlias() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")

        val id = repo.learnProduct("TESCO", "ts semi skmd mlk 2pt", "Semi-skimmed milk 2 pints", groceries.id)

        val product = assertNotNull(repo.observeProducts().first().singleOrNull())
        assertEquals(id, product.id)
        assertEquals("Semi-skimmed milk 2 pints", product.name)
        assertEquals(groceries.id, product.categoryId)
        val alias = assertNotNull(repo.observeAliases().first().singleOrNull())
        assertEquals("TESCO", alias.store)
        assertEquals("ts semi skmd mlk 2pt", alias.rawNameKey)
        assertEquals(id, alias.productId)
    }

    @Test
    fun learningTheSameNameAgainReusesTheProductIgnoringCase() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val household = DataTestSupport.category("Shopping")

        val first = repo.learnProduct("TESCO", "ts semi skmd mlk 2pt", "Semi-skimmed milk 2 pints", groceries.id)
        val second = repo.learnProduct("SAINSBURYS", "sbry semi skimmed milk", "  SEMI-SKIMMED MILK 2 PINTS ", household.id)

        assertEquals(first, second)
        val product = repo.observeProducts().first().single()
        assertEquals("Semi-skimmed milk 2 pints", product.name, "the first spelling is kept")
        assertEquals(household.id, product.categoryId, "the latest category wins")
        assertEquals(2, repo.observeAliases().first().size, "one alias per store")
    }

    @Test
    fun learningAnExistingAliasRepointsItWithoutDuplicating() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")

        val milk = repo.learnProduct("TESCO", "ts milk", "Milk", groceries.id)
        val whole = repo.learnProduct("TESCO", "ts milk", "Whole milk", null)

        assertNotEquals(milk, whole)
        val alias = repo.observeAliases().first().single()
        assertEquals(whole, alias.productId)
        assertNull(repo.observeProducts().first().single { it.id == whole }.categoryId)
        // Learning with no category keeps the one a product already has.
        repo.learnProduct("TESCO", "ts milk 2", "Milk", null)
        assertEquals(groceries.id, repo.observeProducts().first().single { it.id == milk }.categoryId)
    }

    @Test
    fun deletingAReceiptKeepsTheLearnedProducts() = runBlocking<Unit> {
        val shop = shop()
        val milk = repo.learnProduct("TESCO", "ts semi skmd mlk 2pt", "Semi-skimmed milk 2 pints", shop.groceriesId)
        val receiptId = repo.saveReceipt(
            receiptFor(shop),
            listOf(
                ReceiptItem(
                    receiptId = 0, productId = milk, rawName = "TS SEMI SKMD MLK 2PT", price = Money(4_235),
                    categoryId = shop.groceriesId,
                ),
            ),
            listOf(shop.plain.copy(id = 0)),
            replaceTransactionId = shop.plain.id,
        )
        assertEquals(milk, repo.observeItems(receiptId).first().single().productId)

        repo.deleteReceipt(receiptId, mergeBack = true)

        assertEquals(listOf(milk), repo.observeProducts().first().map { it.id })
        assertEquals(1, repo.observeAliases().first().size)
    }
}
